package com.zhiwei.course.controller;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.zhiwei.api.dto.course.CourseSimpleInfoDTO;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.validate.annotations.ParamChecker;
import com.zhiwei.course.constants.CourseErrorInfo;
import com.zhiwei.course.constants.CourseStatus;
import com.zhiwei.course.domain.dto.*;
import com.zhiwei.course.domain.vo.*;
import com.zhiwei.course.service.*;
import com.zhiwei.course.utils.CourseSaveBaseGroup;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import io.swagger.v3.oas.annotations.enums.ParameterIn;

/**
 * 课程controller
 *
 * @ClassName CourseController
 * @Author wusongsong
 * @Date 2022/7/10 15:34
 * @Version
 **/
@Tag(name = "课程相关接口")
@RestController
@RequestMapping("courses")
@Slf4j
@Validated
public class CourseController {

    @Autowired
    private ICourseDraftService courseDraftService;

    @Autowired
    private ICourseCatalogueDraftService courseCatalogueDraftService;

    @Autowired
    private ICourseTeacherDraftService courseTeacherDraftService;

    @Autowired
    private ICourseService courseService;

    @Autowired
    private ICourseCatalogueService courseCatalogueService;

    //todo 二期做
//    @GetMapping("statistics")
//    @Operation(summary = "课程数据统计")
    public CourseStatisticsVO statistics() {
        return null;
    }

    @GetMapping("baseInfo/{id}")
    @Operation(summary = "获取课程基础信息")
    @Parameters({@Parameter(description = "课程id", name = "id", in = ParameterIn.QUERY),
            @Parameter(description = "是否是用于查看页面查看数据，默认是查看,如果不是界面查看数据就是编辑页面使用", name = "see", in = ParameterIn.QUERY)})
    public CourseBaseInfoVO baseInfo(@PathVariable("id") Long id,
                                     @RequestParam(value = "see", required = false, defaultValue = "1") Boolean see) {
        return courseDraftService.getCourseBaseInfo(id, see);
    }

    @PostMapping("baseInfo/save")
    @Operation(summary = "保存课程基本信息")
    @ParamChecker
    //校验非业务限制的字段
    public CourseSaveVO save(@RequestBody @Validated(CourseSaveBaseGroup.class) CourseBaseInfoSaveDTO courseBaseInfoSaveDTO) {
        return courseDraftService.save(courseBaseInfoSaveDTO);
    }

    @GetMapping("catas/{id}")
    @Operation(summary = "获取课程的章节")
    @Parameters({
            @Parameter(description = "课程id", name = "id", in = ParameterIn.QUERY),
            @Parameter(description = "是否是用于查看页面查看数据，默认是查看,如果不是界面查看数据就是编辑页面使用", name = "see", in = ParameterIn.QUERY)
    })
    public List<CataVO> catas(@PathVariable(value = "id", required = false) Long id,
                              @RequestParam(value = "see", required = false, defaultValue = "1") Boolean see,
                              @RequestParam(value = "withPractice", required = false, defaultValue = "1") Boolean withPractice) {
        return courseCatalogueDraftService.queryCourseCatalogues(id, see, withPractice);
    }

    @PostMapping("catas/save/{id}/{step}")
    @Operation(summary = "保存章节")
    @Parameters({
            @Parameter(description = "课程id", name = "id", in = ParameterIn.QUERY),
            @Parameter(description = "步骤", name = "step", in = ParameterIn.QUERY)
    })
    @ParamChecker
    public void catasSave(@RequestBody @Validated List<CataSaveDTO> cataSaveDTOS,
                          @PathVariable("id") Long id, @PathVariable(value = "step",required = false) Integer step) {
        courseCatalogueDraftService.save(id, cataSaveDTOS, step);
    }

    @PostMapping("media/save/{id}")
    @Operation(summary = "课程视频")
    @Parameters({
            @Parameter(description = "课程id", name = "id", in = ParameterIn.QUERY)
    })
    public void mediaSave(@PathVariable("id") Long id, @RequestBody @Valid List<CourseMediaDTO> courseMediaDTOS) {
        courseCatalogueDraftService.saveMediaInfo(id, courseMediaDTOS);
    }

    @PostMapping("subjects/save/{id}")
    @Operation(summary = "保存小节或练习中的题目")
    @Parameters({
            @Parameter(description = "课程id", name = "id", in = ParameterIn.QUERY)
    })
    public void saveSuject(@PathVariable("id") Long id, @RequestBody @Validated List<CataSubjectDTO> cataSubjectDTO) {
        courseCatalogueDraftService.saveSuject(id, cataSubjectDTO);
    }

    @GetMapping("subjects/get/{id}")
    @Operation(summary = "获取小节或练习中的题目（用于编辑）")
    @Parameters({
            @Parameter(description = "课程id", name = "id", in = ParameterIn.QUERY)
    })
    public List<CataSimpleSubjectVO> getSuject(@PathVariable("id") Long id) {
        return courseCatalogueDraftService.getSuject(id);
    }

    @GetMapping("teachers/{id}")
    @Operation(summary = "查询课程相关的老师信息")
    @Parameters({
            @Parameter(description = "课程id", name = "id", in = ParameterIn.QUERY),
            @Parameter(description = "是否是用于查看页面查看数据，默认是查看,如果不是界面查看数据就是编辑页面使用", name = "see", in = ParameterIn.QUERY)
    })
    public List<CourseTeacherVO> teacher(@PathVariable("id") Long id,
                                         @RequestParam(value = "see", required = false, defaultValue = "1") Boolean see) {
        return courseTeacherDraftService.queryTeacherOfCourse(id, see);
    }

    @PostMapping("teachers/save")
    @Operation(summary = "保存老师信息")
    public void teachersSave(@RequestBody @Validated CourseTeacherSaveDTO courseTeacherSaveDTO) {
        courseTeacherDraftService.save(courseTeacherSaveDTO);
    }


    @PostMapping("upShelf")
    @Operation(summary = "课程上架")
    public void upShelf(@RequestBody @Validated CourseIdDTO courseIdDTO) {
        courseDraftService.upShelf(courseIdDTO.getId());
    }

    @GetMapping("checkBeforeUpShelf/{id}")
    @Operation(summary = "课程上架前校验")
    public void checkBeforeUpShelf(@PathVariable("id") Long id){
        courseDraftService.checkBeforeUpShelf(id);
    }

    @PostMapping("downShelf")
    @Operation(summary = "课程下架")
    public void downShelf(@RequestBody @Validated CourseIdDTO courseIdDTO) {
        courseDraftService.downShelf(courseIdDTO.getId());
    }

    /**
     * 先去删除加上数据删除后，再去删除草稿
     *
     * @param id
     */
    @DeleteMapping("delete/{id}")
    @Operation(summary = "课程删除")
    @Parameters({
            @Parameter(description = "id", name = "id", in = ParameterIn.QUERY)
    })
    public void deleteById(@PathVariable("id") Long id) {
        courseService.delete(id);
    }

    @Operation(summary = "根根条件列表获取课程信息")
    @GetMapping("/simpleInfo/list")
    public List<CourseSimpleInfoDTO> getSimpleInfoList(CourseSimpleInfoListDTO courseSimpleInfoListDTO) {
        return courseService.getSimpleInfoList(courseSimpleInfoListDTO);
    }

    @Operation(summary = "根据课程id，查询所有章节的序号")
    @GetMapping("/catas/index/list/{id}")
    @Parameters(@Parameter(description = "课程id", name = "id", in = ParameterIn.QUERY))
    public List<CataSimpleInfoVO> catasIndexList(@PathVariable("id") Long id) {
        return courseCatalogueService.getCatasIndexList(id);
    }

    @Operation(summary = "生成练习id")
    @GetMapping("generator")
    public CourseCataIdVO generator() {
        return new CourseCataIdVO(IdWorker.getId());
    }

    @Operation(summary = "管理端课程搜索接口")
    @GetMapping("/page")
    public PageDTO<CoursePageVO> queryForPage(CoursePageQuery coursePageQuery) {
        // 状态是必填的，而且不只是个筛选条件 —— 它决定查哪张表：
        // 待上架(1)/已下架(3) 查草稿表 course_draft，已上架(2)/已完结(4) 查正式表 course。
        // 所以「不传 status」没法默认成任何一边，必须明确拒绝。
        // 原来没有这道校验，null 直接进 CourseStatus.equals 拆箱 NPE，
        // 冒到全局异常处理器变成 500「服务器内部异常」+ 空消息，
        // 管理端看到的只有「服务器内部异常」，完全看不出是少传了个参数。
        Integer status = coursePageQuery.getStatus();
        if (status == null) {
            throw new BadRequestException(CourseErrorInfo.Msg.COURSE_PAGE_STATUS_NULL);
        }
        // 只接受 1~4。传个 99 进来虽然查不出数据（不会报错），但会被当成「非草稿状态」
        // 路由到正式表，等于用错误的表回答了一个错误的问题 —— 静默给出空结果比直接报错更难查。
        CourseStatus courseStatus = CourseStatus.of(status);
        if (courseStatus == null) {
            throw new BadRequestException(CourseErrorInfo.Msg.COURSE_PAGE_STATUS_ILLEGAL);
        }
        // 用枚举本身做分支，不再走 CourseStatus.XXX.equals(status) 那个重载：
        // 上面已经拿到枚举实例，再比一次数字是多余的，也少一层拆箱风险。
        if (courseStatus == CourseStatus.NO_UP_SHELF || courseStatus == CourseStatus.DOWN_SHELF) {
            //待上架已下架查询草稿
            return courseDraftService.queryForPage(coursePageQuery);
        } else {
            //已上架已完结查询正式数据
            return courseService.queryForPage(coursePageQuery);
        }
    }

    @Operation(summary = "校验课程名称是否已经存在")
    @GetMapping("/checkName")
    @Parameters({
            @Parameter(description = "id", name = "id", in = ParameterIn.QUERY),
            @Parameter(description = "课程名称", name = "name", in = ParameterIn.QUERY)
    })
    public NameExistVO checkNameExist(@RequestParam(value = "id",required = false) Long id,
                                      @RequestParam(value = "name") String name){
        return courseService.checkName(name, id);
    }

    @Operation(summary = "查询课程基本信息、目录、学习进度")
    @GetMapping("/{id}/catalogs")
    public CourseAndSectionVO queryCourseAndCatalogById(@PathVariable("id") Long courseId){
        return courseService.queryCourseAndCatalogById(courseId);
    }
}
