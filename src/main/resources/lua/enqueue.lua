-- 대기열 원자적 진입. KEYS[1]=queue, KEYS[2]=activeSlots, KEYS[3]=seq / ARGV[1]=slotId, ARGV[2]=userId
-- 반환: 0=이미 대기중, >0=부여된 FIFO 시퀀스
if redis.call('ZSCORE', KEYS[1], ARGV[2]) then
    return 0
end
local seq = redis.call('INCR', KEYS[3])
redis.call('ZADD', KEYS[1], seq, ARGV[2])
redis.call('SADD', KEYS[2], ARGV[1])
return seq
