package com.campus.prededuct;

import com.campus.common.exception.ClientException;
import com.campus.framework.idempotent.CampusIdempotent;
import com.campus.reservation.dto.req.ReservationCreateReqDTO;
import com.campus.reservation.dto.resp.MyReservationRespDTO;
import com.campus.reservation.errorcode.ReservationErrorCode;
import com.campus.reservation.service.ReservationService;
import com.campus.resource.dto.resp.SlotRespDTO;
import com.campus.resource.service.ResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 预约闸门（阶段 17 + 18）：图书馆座位 / 体育场馆 / 讲座的预扣入口。
 *
 * <p><b>跨时间片全成或全败</b>：一次 2 小时预约要占 2 个时间片，预扣必须"两片都有余量才扣"——
 * 由 Lua 脚本内"先全部校验、再逐个扣减"保证（与 12306 "北京南→南京南逐段扣票"同一语义）。
 * 任何一片不足即整体拒绝，绝不会出现"扣了 1 片、另 1 片没扣"的脏状态。</p>
 *
 * <p>时间片列表来自 {@link ResourceService#slots}——这一步读的是<b>阶段 16 的缓存</b>，
 * 所以闸门本身几乎不产生数据库压力（缓存命中时是纯内存操作）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationGate {

    private final PreDeductService preDeduct;
    private final ReservationService reservationService;
    private final ResourceService resourceService;

    /**
     * 创建预约（预扣闸门 + 幂等）。
     *
     * <p>幂等键 = 用户 + 资源 + 日期 + 起止时间（同一时段重复提交只生效一次）；
     * 用户维度由切面自动拼接（见 {@code CampusIdempotentAspect}）。</p>
     */
    @CampusIdempotent(prefix = "reserve",
            key = "#req.resourceId + ':' + #req.slotDate + ':' + #req.startTime + ':' + #req.endTime",
            ttlSeconds = 5, message = "预约请求正在处理，请勿重复提交")
    public MyReservationRespDTO create(ReservationCreateReqDTO req) {
        // ① 取目标时间片（走缓存；资源不存在/布隆拦截的异常直接上抛）
        List<SlotRespDTO> target = targetSlots(req);
        if (target.isEmpty()) {
            // 无匹配时间片：不预扣，交由业务层给出准确错误（时段不合法 / 无库存）
            return reservationService.create(req);
        }

        // ② 组装预扣 key（每个片一个计数器）
        List<String> keys = new ArrayList<>(target.size());
        List<PreDeductService.KeyMeta> metas = new ArrayList<>(target.size());
        List<Supplier<Integer>> loaders = new ArrayList<>(target.size());
        for (SlotRespDTO slot : target) {
            String key = PreDeductService.slotKey(slot.getId());
            keys.add(key);
            metas.add(new PreDeductService.KeyMeta(key, PreDeductService.TYPE_SLOT, slot.getId(),
                    req.getResourceId(), req.getSlotDate(), "", System.currentTimeMillis()));
            final Integer capacity = slot.getCapacity();
            final Integer booked = slot.getBooked();
            loaders.add(() -> Math.max(0, capacity - booked));
        }

        // ③ 原子预扣（全成或全败）
        int r = preDeduct.ensureThenDeduct(keys, metas, loaders, 1);
        if (r == PreDeductService.INSUFFICIENT) {
            log.info("[预扣闸门] 拦截预约 resourceId={} {}~{}（Redis 判定余量不足，未查库）",
                    req.getResourceId(), req.getStartTime(), req.getEndTime());
            throw new ClientException("预扣闸门拦截预约请求: resourceId=" + req.getResourceId(),
                    "手慢了，该时段刚被约满", ReservationErrorCode.SOLD_OUT);
        }

        // ④ 进数据库（条件更新仍是最终裁决）
        try {
            return reservationService.create(req);
        } catch (RuntimeException e) {
            // 数据库拒绝（时间冲突 / 真满 / 重复 / 惩罚限制）→ 回补预扣
            preDeduct.releaseQuietly(keys, 1);
            throw e;
        }
    }

    /** 目标时间片：与 ReservationService 同一过滤口径（startTime ∈ [start, end)） */
    private List<SlotRespDTO> targetSlots(ReservationCreateReqDTO req) {
        List<SlotRespDTO> slots = resourceService.slots(req.getResourceId(), req.getSlotDate());
        List<SlotRespDTO> target = new ArrayList<>();
        for (SlotRespDTO s : slots) {
            if (!s.getStartTime().isBefore(req.getStartTime()) && s.getStartTime().isBefore(req.getEndTime())) {
                target.add(s);
            }
        }
        return target;
    }
}
