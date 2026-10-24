package com.zhiwei.learning.service.impl;

import com.zhiwei.api.client.user.UserClient;
import com.zhiwei.api.dto.user.UserDTO;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.DateUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.learning.Utils.TableInfoContext;
import com.zhiwei.learning.constants.RedisConstants;
import com.zhiwei.learning.domian.po.PointsBoard;
import com.zhiwei.learning.domian.query.PointsBoardQuery;
import com.zhiwei.learning.domian.vo.PointsBoardItemVO;
import com.zhiwei.learning.domian.vo.PointsBoardVO;
import com.zhiwei.learning.mapper.PointsBoardMapper;
import com.zhiwei.learning.service.IPointsBoardService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.BoundZSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 学霸天梯榜 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard> implements IPointsBoardService {
    private final StringRedisTemplate redisTemplate;
    private final UserClient userClient;
    private final PointsBoardMapper pointsBoardMapper;


    @Override
    public PointsBoardVO queryPointsBoardBySeason(PointsBoardQuery query) {
        Long season = query.getSeason();
        boolean isCurrent = season == null || season == 0;
        // 历史赛季先看分表在不在：分表只在「那个赛季确实有榜单数据」时才被任务建出来，
        // 赛季表里有赛季但没有分表是常态。以前直接 select 不存在的表 → 500「服务器内部异常」，
        // 前端只能显示「加载失败 + 重新加载」，其实语义只是「这个赛季没有数据」。
        if (!isCurrent && !historyTableExists(season)) {
            log.warn("历史赛季 {} 没有对应的榜单分表 points_board_{}，按空榜返回", season, season);
            return new PointsBoardVO();
        }
        LocalDateTime now = LocalDateTime.now();
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + now.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        PointsBoard myBoard = isCurrent ? queryMyCurrentBoard(key) : queryMyHistoryBoard(season);
        List<PointsBoard> boardList = isCurrent ? queryCurrentBoardList(key, query.getPageNo(), query.getPageSize()) : queryHistoryBoardList(query);
        PointsBoardVO vo = new PointsBoardVO();
        if (myBoard != null) {
            vo.setRank(myBoard.getRank());
            vo.setPoints(myBoard.getPoints());
        }
        if (CollUtils.isEmpty(boardList)) {
            return vo;
        }

        Set<Long> userIds = boardList.stream().map(PointsBoard::getUserId).collect(Collectors.toSet());
        List<UserDTO> users = userClient.queryUserByIds(userIds);
        Map<Long, String> map = new HashMap<>(users.size());
        if (CollUtils.isNotEmpty(users)) {
            map = users.stream().collect(Collectors.toMap(UserDTO::getId, UserDTO::getName));
        }
        List<PointsBoardItemVO> voList = new ArrayList<>(boardList.size());
        for (PointsBoard p : boardList) {
            PointsBoardItemVO itemVO = new PointsBoardItemVO();
            // 用户注销/被删后，user-service 的 queryUserByIds 走 WHERE id IN (...)，直接不返回他，
            // map.get 就是 null，前端只能显示「--」，和「接口挂了」长得一模一样，分不清。
            // 历史榜单必须保留（不能因为人没了就把榜单记录删掉），所以这里兜一个占位名。
            itemVO.setName(map.getOrDefault(p.getUserId(), "已注销用户"));
            itemVO.setPoints(p.getPoints());
            itemVO.setRank(p.getRank());
            voList.add(itemVO);
        }
        vo.setBoardList(voList);
        return vo;
    }

    private List<PointsBoard> queryHistoryBoardList(PointsBoardQuery query) {
        Long season = query.getSeason();
        String tableName = "points_board_" + season;
        // 兜底再判一次：正常情况下上层已经拦过，这里防的是以后有别的调用方直接进来
        if (pointsBoardMapper.countTableByName(tableName) == 0) {
            return CollUtils.emptyList();
        }
        TableInfoContext.setInfo(tableName);
        try {
            List<PointsBoard> boardList = pointsBoardMapper.queryHistoryBoardBySeason(tableName);
            if (CollUtils.isEmpty(boardList)) {
                return CollUtils.emptyList();
            }
            boardList.forEach(p -> {
                p.setRank(p.getId().intValue());
                p.setSeason(season.intValue());
            });
            return boardList;
        } finally {
            TableInfoContext.clear();
        }
    }


    @Override
    public List<PointsBoard> queryCurrentBoardList(String key, int pageNo, int pageSize) {
        int from = (pageNo - 1) * pageSize;
        // ★ 必须是 reverseRangeWithScores（降序）：ZSet 默认 range 是「分数从低到高」，
        // 用 rangeWithScores 取出来的第一名其实是积分最低的那个人，而下面又是按位置
        // 从 1 开始赋 rank —— 结果就是「积分 2 分的排第一、24 分的排第二」。
        // 我的排名用的是 reverseRank（降序），两处口径必须一致。
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, from, from + pageSize - 1);
        if (CollUtils.isEmpty(tuples)) {
            return CollUtils.emptyList();
        }
        List<PointsBoard> list = new ArrayList<>(tuples.size());
        int rage = from + 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            PointsBoard p = new PointsBoard();
            String userId = tuple.getValue();
            Double points = tuple.getScore();
            if (userId == null || points == null) {
                continue;
            }
            p.setUserId(Long.valueOf(userId));
            p.setPoints(points.intValue());
            p.setRank(rage++);
            list.add(p);
        }
        return list;

    }

    private PointsBoard queryMyHistoryBoard(Long season) {
        if (season == null) {
            return null;
        }
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + season;
        return queryMyBoard(key);
    }

    /** 历史赛季的榜单分表是否存在（不存在 = 该赛季没有榜单数据，不是错误） */
    private boolean historyTableExists(Long season) {
        return season != null && pointsBoardMapper.countTableByName("points_board_" + season) > 0;
    }

    private PointsBoard queryMyCurrentBoard(String key) {
        return queryMyBoard(key);
    }

    private PointsBoard queryMyBoard(String key) {
        BoundZSetOperations<String, String> ops = redisTemplate.boundZSetOps(key);
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return null;
        }
        Double points = ops.score(userId.toString());
        Long rank = ops.reverseRank(userId.toString());
        PointsBoard p = new PointsBoard();
        p.setPoints(points == null ? 0 : points.intValue());
        p.setRank(rank == null ? 0 : rank.intValue() + 1);
        return p;
    }


    @Override
    public void createPointsBoardTableBySeason(Integer season) {
        pointsBoardMapper.createPointsBoardTableBySeason("points_board_" + season);
    }
}
