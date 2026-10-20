package com.campus.prededuct;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campus.course.dao.entity.CourseClassDO;
import com.campus.course.dao.mapper.CourseClassMapper;
import com.campus.resource.dao.entity.ResourceSlotDO;
import com.campus.resource.dao.mapper.ResourceSlotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 预扣闸门的"数据库真值"查询（阶段 17）。
 *
 * <p>真相定义（唯一口径）：</p>
 * <ul>
 *   <li><b>教学班</b>：{@code 剩余 = capacity - selected}（t_course_class 单行，主键点查）</li>
 *   <li><b>时间片</b>：{@code 剩余 = capacity - booked}（t_resource_slot 按 (resource_id, slot_date)
 *       单分片查询后在内存中定位 slot —— 分片键是 resource_id，按主键点查会全路由）</li>
 * </ul>
 *
 * <p>这些查询只在对账/观测时发生（30 秒一轮 × 少量热点 key），不在业务请求路径上。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreDeductTruth {

    private final CourseClassMapper courseClassMapper;
    private final ResourceSlotMapper resourceSlotMapper;

    /** 单 key 真值；返回 null 表示对象不存在（数据已删） */
    public Integer truthOf(PreDeductService.KeyMeta meta) {
        try {
            if (PreDeductService.TYPE_CLASS.equals(meta.type())) {
                CourseClassDO cls = courseClassMapper.selectById(meta.refId());
                return cls == null ? null : Math.max(0, cls.getCapacity() - cls.getSelected());
            }
            Map<Long, Integer> slots = slotRemaining(meta.resourceId(), meta.date());
            return slots.get(meta.refId());
        } catch (Exception e) {
            log.warn("[预扣] 真值查询失败: key={} {}", meta.key(), e.getMessage());
            return null;
        }
    }

    /** 某一资源某天的全部片剩余量（一次单分片查询，供批量对账使用） */
    public Map<Long, Integer> slotRemaining(Long resourceId, java.time.LocalDate date) {
        Map<Long, Integer> map = new HashMap<>();
        if (resourceId == null || date == null) {
            return map;
        }
        List<ResourceSlotDO> slots = resourceSlotMapper.selectList(Wrappers.lambdaQuery(ResourceSlotDO.class)
                .eq(ResourceSlotDO::getResourceId, resourceId)
                .eq(ResourceSlotDO::getSlotDate, date));
        for (ResourceSlotDO s : slots) {
            map.put(s.getId(), Math.max(0, s.getCapacity() - s.getBooked()));
        }
        return map;
    }

    /** 展示名（前端表格用）：教学班名 / 资源片时间 */
    public String labelOf(PreDeductService.KeyMeta meta) {
        try {
            if (PreDeductService.TYPE_CLASS.equals(meta.type())) {
                CourseClassDO cls = courseClassMapper.selectById(meta.refId());
                return cls == null ? ("教学班#" + meta.refId()) : cls.getClassName();
            }
            return "片#" + meta.refId() + "（" + meta.date() + "）";
        } catch (Exception e) {
            return "#" + meta.refId();
        }
    }
}
