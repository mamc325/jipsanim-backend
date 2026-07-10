-- 예약권 원자적 발급. KEYS[1]=token, KEYS[2]=queue / ARGV[1]=ttlMs
-- 반환: nil(false)=발급 안 함(이미 토큰 있음/큐 빔), userId=발급됨
if redis.call('EXISTS', KEYS[1]) == 1 then
    return false
end
local popped = redis.call('ZPOPMIN', KEYS[2])
if #popped == 0 then
    return false
end
redis.call('SET', KEYS[1], popped[1], 'PX', tonumber(ARGV[1]))
return popped[1]
