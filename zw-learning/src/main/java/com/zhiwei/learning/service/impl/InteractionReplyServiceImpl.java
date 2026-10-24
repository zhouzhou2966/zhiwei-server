package com.zhiwei.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.zhiwei.api.client.remark.RemarkClient;
import com.zhiwei.api.client.user.UserClient;
import com.zhiwei.api.dto.user.UserDTO;
import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.learning.domian.dto.ReplyDTO;
import com.zhiwei.learning.domian.enums.QuestionStatus;
import com.zhiwei.learning.domian.po.InteractionQuestion;
import com.zhiwei.learning.domian.po.InteractionReply;
import com.zhiwei.learning.domian.query.ReplyPageQuery;
import com.zhiwei.learning.domian.vo.ReplyVO;
import com.zhiwei.learning.mapper.InteractionQuestionMapper;
import com.zhiwei.learning.mapper.InteractionReplyMapper;
import com.zhiwei.learning.service.IInteractionReplyService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * <p>
 * 互动问题的回答或评论 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-10
 */
@Service
@RequiredArgsConstructor
public class InteractionReplyServiceImpl extends ServiceImpl<InteractionReplyMapper, InteractionReply> implements IInteractionReplyService {
    private final InteractionQuestionMapper questionMapper;
    private final UserClient userClient;
    private final RemarkClient remarkClient;
    private final RabbitMqHelper mqHelper;

    @Override
    public void removeByQuestionId(Long id) {
        if (id == null) {
            return;
        }
        remove(new LambdaUpdateWrapper<InteractionReply>().eq(InteractionReply::getQuestionId, id));
    }


    @Override
    @Transactional
    /**
     * 保存回复信息的方法
     * @param replyDTO 包含回复数据的传输对象
     */
    public void saveReply(ReplyDTO replyDTO) {
        // 1.获取当前登录用户ID
        Long userId = UserContext.getUserId();
        InteractionReply reply = BeanUtils.copyBean(replyDTO, InteractionReply.class);
        reply.setUserId(userId);

        // 2.保存回答/评论到数据库
        save(reply);

        // 3.判断是否是【评论】（answerId有值 = 评论）
        boolean isComment = replyDTO.getAnswerId() != null && replyDTO.getAnswerId() > 0;
        if (isComment) {
            // 是评论：给所属的一级回答的回复次数 +1
            lambdaUpdate()
                    .setSql("reply_times = reply_times + 1")
                    .eq(InteractionReply::getId, replyDTO.getAnswerId())
                    .update();
        } else {
            // 是回答：更新问题的最新回答ID + 回答次数+1
            LambdaUpdateWrapper<InteractionQuestion> wrapper = new LambdaUpdateWrapper<InteractionQuestion>()
                    .eq(InteractionQuestion::getId, replyDTO.getQuestionId())
                    .set(InteractionQuestion::getLatestAnswerId, reply.getId())
                    .setSql("answer_times = answer_times + 1");
            int updated = questionMapper.update(wrapper);
            if (updated > 0) {
                try {
                    mqHelper.send(
                            MqConstants.Exchange.LEARNING_EXCHANGE,
                            MqConstants.Key.WRITE_REPLY,
                            userId
                    );
                } catch (Exception e) {
                    log.error("推送回答积分消息异常", e);
                }

            }
        }

        // 4.判断是否学生提交 → 是则设置问题状态为待审核
        boolean isStudent = Boolean.TRUE.equals(replyDTO.getIsStudent());
        if (isStudent) {
            questionMapper.update(new LambdaUpdateWrapper<InteractionQuestion>()
                    .eq(InteractionQuestion::getId, replyDTO.getQuestionId())
                    .set(InteractionQuestion::getStatus, QuestionStatus.UN_CHECK));
        }
    }


    /**
     * 分页查询回复列表（支持按问题ID / 回答ID查询）
     *
     * @param query 分页查询条件
     * @return 封装好的回复分页VO
     */
    public PageDTO<ReplyVO> listReplies(ReplyPageQuery query) {
        // 1. 校验参数：问题ID和回答ID不能同时为空，否则直接返回空分页
        if (query.getQuestionId() == null && query.getAnswerId() == null) {
            return PageDTO.empty(0L, 0L);
        }

        // 2. 分页查询回复数据（动态条件：只传有效值）
        Page<InteractionReply> page = lambdaQuery()
                .eq(query.getQuestionId() != null, InteractionReply::getQuestionId, query.getQuestionId())
                .eq(query.getAnswerId() != null && query.getAnswerId() > 0, InteractionReply::getAnswerId, query.getAnswerId())
                .eq(InteractionReply::getHidden, false)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());

        // 获取分页数据列表
        List<InteractionReply> records = page.getRecords();
        // 无数据直接返回空分页
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page.getTotal(), page.getPages());
        }

        // 3. 提取所有需要查询的用户ID（回复人ID + 被回复人ID），去重+去null
        Set<Long> userIds = getUserIds(records);

        // 4. 批量查询用户信息（避免循环查询，解决N+1问题）
        List<UserDTO> users = userClient.queryUserByIds(userIds);
        // 转成ID->用户信息Map，方便后续O(1)快速查找
        Map<Long, UserDTO> userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));

        // 5. 封装VO返回
        List<ReplyVO> voList = getReplyVOList(records, userMap);

        // 6. 组装分页结果返回
        return PageDTO.of(page, voList);
    }

    public PageDTO<ReplyVO> listRepliesAdmin(ReplyPageQuery query) {
        // 1. 校验参数：问题ID和回答ID不能同时为空，否则直接返回空分页
        if (query.getQuestionId() == null && query.getAnswerId() == null) {
            return PageDTO.empty(0L, 0L);
        }

        // 2. 分页查询回复数据（动态条件：只传有效值）
        Page<InteractionReply> page = lambdaQuery()
                .eq(query.getQuestionId() != null, InteractionReply::getQuestionId, query.getQuestionId())
                .eq(query.getAnswerId() != null && query.getAnswerId() > 0, InteractionReply::getAnswerId, query.getAnswerId())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());

        // 获取分页数据列表
        List<InteractionReply> records = page.getRecords();
        // 无数据直接返回空分页
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page.getTotal(), page.getPages());
        }

        // 3. 提取所有需要查询的用户ID（回复人ID + 被回复人ID），去重+去null
        Set<Long> userIds = getUserIds(records);

        // 4. 批量查询用户信息（避免循环查询，解决N+1问题）
        List<UserDTO> users = userClient.queryUserByIds(userIds);
        // 转成ID->用户信息Map，方便后续O(1)快速查找
        Map<Long, UserDTO> userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));

        // 5. 封装VO返回
        List<ReplyVO> voList = getReplyVOList(records, userMap);

        // 6. 组装分页结果返回
        return PageDTO.of(page, voList);
    }

    private Set<Long> getUserIds(List<InteractionReply> records) {
        Set<Long> userIds = records
                .stream()
                .flatMap(r -> Stream.of(r.getUserId(), r.getTargetUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return userIds;
    }

    private List<ReplyVO> getReplyVOList(List<InteractionReply> records, Map<Long, UserDTO> userMap) {
        List<ReplyVO> voList = new ArrayList<>(records.size());
        Set<Long> replyIds = records.stream().map(InteractionReply::getId).collect(Collectors.toSet());
        for (InteractionReply r : records) {
            // 对象拷贝：基础属性复制
            ReplyVO vo = BeanUtils.copyBean(r, ReplyVO.class);
            if (Objects.equals(r.getAnonymity(), false)) {  // 非匿名
                // 填充回复人信息
                UserDTO user = userMap.get(r.getUserId());
                if (user != null) {
                    vo.setUserName(user.getName());
                    vo.setUserIcon(user.getIcon());
                    vo.setUserType(user.getType());
                }
            }
            // 填充被回复人信息
            UserDTO targetUser = userMap.get(r.getTargetUserId());
            if (targetUser != null) {
                vo.setTargetUserName(targetUser.getName());
            }

            voList.add(vo);
        }
        // 填充点赞信息
        Set<Long> bizLiked;
        try {
            // 批量查询当前用户对这些回复的点赞状态（回答/评论属于问答业务类型）
            bizLiked = remarkClient.isBizLike(replyIds, "QA");
        } catch (Exception e) {
            // 点赞服务异常时，默认都设为未点赞，不影响列表展示
            bizLiked = Collections.emptySet();
        }
        Set<Long> finalBizLiked = bizLiked;
        voList.forEach(vo -> vo.setLiked(finalBizLiked.contains(vo.getId())));
        return voList;
    }

    @Override
    public void updateReplyHidden(Long id, Boolean hidden) {
        if (id == null) {
            return;
        }
        InteractionReply reply = getById(id);
        if (reply == null) {
            return;
        }
        lambdaUpdate()
                .set(InteractionReply::getHidden, hidden)
                .eq(InteractionReply::getId, id)
                .or()
                .eq(InteractionReply::getAnswerId, id)
                .or()
                .eq(InteractionReply::getTargetReplyId, id)
                .update();
    }


}
