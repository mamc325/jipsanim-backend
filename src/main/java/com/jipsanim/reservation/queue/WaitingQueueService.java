package com.jipsanim.reservation.queue;

import com.jipsanim.reservation.config.ReservationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis Sorted Set 대기열 + TTL 예약권. 원자 연산은 Lua 로 수행(Constitution II).
 * 정리 2종: releaseTokenIfOwner(token만, 큐 유지) / cleanupSlot(전체). (spec §6-4)
 */
@Service
public class WaitingQueueService {

    static final String ACTIVE_SLOTS = "waiting:slots";
    static final String SEQ = "waiting:seq";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> enqueueScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> tryIssueScript;
    private final RedisScript<Long> releaseTokenIfOwnerScript;
    private final long tokenTtlMillis;

    @SuppressWarnings("rawtypes")
    public WaitingQueueService(StringRedisTemplate redis,
                               RedisScript<Long> enqueueScript,
                               RedisScript<List> tryIssueScript,
                               RedisScript<Long> releaseTokenIfOwnerScript,
                               ReservationProperties properties) {
        this.redis = redis;
        this.enqueueScript = enqueueScript;
        this.tryIssueScript = tryIssueScript;
        this.releaseTokenIfOwnerScript = releaseTokenIfOwnerScript;
        this.tokenTtlMillis = properties.tokenTtlMillis();
    }

    static String queueKey(Long slotId) {
        return "waiting:visit-slot:" + slotId;
    }

    static String tokenKey(Long slotId) {
        return "reservation-token:" + slotId;
    }

    static String invitationKey(Long slotId) {
        return "invitation:" + slotId;
    }

    /** 대기열 진입(원자). @return true=진입, false=이미 대기중 */
    public boolean enqueue(Long slotId, Long userId) {
        Long seq = redis.execute(enqueueScript, List.of(queueKey(slotId), ACTIVE_SLOTS, SEQ),
                str(slotId), str(userId));
        return seq != null && seq > 0;
    }

    /** 대기 순번(1-based). 큐에 없으면 null(토큰 보유 등). */
    public Long rank(Long slotId, Long userId) {
        Long rank = redis.opsForZSet().rank(queueKey(slotId), str(userId));
        return rank == null ? null : rank + 1;
    }

    /** 원자적 발급. @return {userId, invitationSeq} 또는 null(이미 토큰/빈 큐). invitationSeq 는 Outbox 멱등키. */
    public IssuedInvitation tryIssue(Long slotId) {
        @SuppressWarnings("unchecked")
        List<Object> result = redis.execute(tryIssueScript,
                List.of(tokenKey(slotId), queueKey(slotId), invitationKey(slotId)),
                String.valueOf(tokenTtlMillis));
        if (result == null || result.size() < 2) {
            return null;
        }
        Long userId = Long.valueOf(result.get(0).toString());
        long seq = Long.parseLong(result.get(1).toString());
        return new IssuedInvitation(userId, seq);
    }

    public boolean hasToken(Long slotId, Long userId) {
        return str(userId).equals(tokenOwner(slotId));
    }

    public String tokenOwner(Long slotId) {
        return redis.opsForValue().get(tokenKey(slotId));
    }

    /** 예약권 잔여 TTL(초). 없으면 <=0. */
    public long tokenTtlSeconds(Long slotId) {
        Long ms = redis.getExpire(tokenKey(slotId), TimeUnit.MILLISECONDS);
        return ms == null || ms < 0 ? 0 : ms / 1000;
    }

    /** 소유자일 때만 토큰 삭제(큐 유지) — 실패/만료/read-repair. */
    public void releaseTokenIfOwner(Long slotId, Long userId) {
        redis.execute(releaseTokenIfOwnerScript, List.of(tokenKey(slotId)), str(userId));
    }

    /** 토큰+큐+active-set 전체 삭제 — 확정/마감/슬롯만료. */
    public void cleanupSlot(Long slotId) {
        redis.delete(tokenKey(slotId));
        redis.delete(queueKey(slotId));
        redis.opsForSet().remove(ACTIVE_SLOTS, str(slotId));
    }

    public boolean isQueueEmpty(Long slotId) {
        Long size = redis.opsForZSet().zCard(queueKey(slotId));
        return size == null || size == 0;
    }

    /** sweep: 큐/토큰이 모두 빈 슬롯을 sweep 대상에서 제외(토큰/큐는 건드리지 않음) */
    public void removeActiveSlot(Long slotId) {
        redis.opsForSet().remove(ACTIVE_SLOTS, str(slotId));
    }

    /** sweep 대상 슬롯 집합. */
    public Set<Long> activeSlots() {
        Set<String> members = redis.opsForSet().members(ACTIVE_SLOTS);
        return members == null ? Set.of()
                : members.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    private String str(Long value) {
        return String.valueOf(value);
    }
}
