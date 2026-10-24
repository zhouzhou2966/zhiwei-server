package com.zhiwei.message.websocket;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiwei.message.constants.ChatConstants;
import com.zhiwei.message.domain.po.Sensitive;
import com.zhiwei.message.mapper.SensitiveMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DFA（Deterministic Finite Automaton，确定有限状态机）违禁词过滤器。
 *
 * <p>为什么用 DFA 而不是正则 / 逐词 contains：
 * <ul>
 *   <li>逐词 contains：词库 n 个词就要对文本做 n 次 indexOf，O(n·m)；词库到万级不可用。</li>
 *   <li>把 n 个词拼成一个大正则交替项：每次匹配都要从头尝试所有分支，且引擎回溯
 *       在最坏情况退化为指数级，还有 ReDoS 风险。</li>
 *   <li>DFA：所有词建一棵 Trie 前缀树，扫描文本一遍（O(m)），公共前缀只比较一次，
 *       匹配代价与词库规模无关——词库 1 万个还是 10 万个，都是文本长度决定耗时。</li>
 * </ul>
 *
 * <p>线程安全设计：词库整体构建成一个不可变 Trie，替换用 volatile 引用整体换新。
 * 读路径（每条聊天消息）无锁；reload 时旧 Trie 等在途匹配跑完即被 GC，
 * 不存在「读到构建到一半的树」的中间态。
 *
 * <p>已知短板（面试可主动讲）：
 * <ul>
 *   <li>未做分隔符跳过，「敏 感 词」这类插空写法可绕过——工程上通常在匹配层
 *       预先剔除分隔字符，会牺牲少量准确率，此处为保持实现清晰未引入；</li>
 *   <li>纯 Trie 不处理变体（拼音、形近字），需要配合形近字归一化表扩展。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DfaWordFilter {

    private final SensitiveMapper sensitiveMapper;

    /**
     * 当前生效的 Trie 根节点。volatile 保证 reload 后所有线程立即可见新词库。
     */
    private volatile TrieNode root = new TrieNode();

    /**
     * 词库是否已加载成功（加载失败时降级为不过滤，只告警——聊天主链路不能因为
     * 词库故障整体不可用）
     */
    private volatile boolean ready = false;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        reload();
    }

    /**
     * 重新从数据库加载违禁词并重建 Trie。
     * 管理端增删违禁词后调用本方法刷新本机，同时经 Redis 广播通知其他实例。
     */
    public void reload() {
        try {
            List<Sensitive> words = sensitiveMapper.selectList(Wrappers.emptyWrapper());
            TrieNode newRoot = new TrieNode();
            int count = 0;
            for (Sensitive sensitive : words) {
                String word = sensitive.getSensitives();
                if (word == null || word.isBlank()) {
                    continue;
                }
                insert(newRoot, word.toLowerCase());
                count++;
            }
            this.root = newRoot;
            this.ready = true;
            log.info("违禁词库加载完成，共 {} 个词", count);
        } catch (Exception e) {
            // 降级：保留旧词库继续服务，聊天链路不中断
            log.error("违禁词库加载失败，沿用旧词库", e);
        }
    }

    /**
     * 过滤文本：把所有命中的违禁词整体替换为 ***。
     *
     * @return 过滤后的文本；词库未就绪时原样返回
     */
    public String filter(String text) {
        if (text == null || text.isEmpty() || !ready) {
            return text;
        }
        String lower = text.toLowerCase();
        StringBuilder sb = null;
        // i：扫描起点。每个起点都沿 Trie 尽量往下走，记录最长的完整命中
        for (int i = 0; i < lower.length(); i++) {
            TrieNode node = root;
            int matchEnd = -1;
            for (int j = i; j < lower.length(); j++) {
                node = node.children.get(lower.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.end) {
                    // 记录最长命中而不是立即返回：词库同时有 AB 和 ABC 时，AB 会被 ABC 掩盖
                    matchEnd = j;
                }
            }
            if (matchEnd >= 0) {
                if (sb == null) {
                    sb = new StringBuilder(text);
                }
                sb.replace(i, matchEnd + 1, ChatConstants.MASK_TEXT);
                // 从替换点之后继续扫，起点跳到遮盖段末尾
                i = matchEnd;
            }
        }
        return sb == null ? text : sb.toString();
    }

    private void insert(TrieNode root, String word) {
        TrieNode cur = root;
        for (int i = 0; i < word.length(); i++) {
            cur = cur.children.computeIfAbsent(word.charAt(i), k -> new TrieNode());
        }
        cur.end = true;
    }

    /**
     * Trie 节点。children 用 HashMap 而不是数组：要支持中文，字符集是稀疏的。
     */
    private static class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        boolean end;
    }
}
