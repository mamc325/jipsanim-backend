-- 소유자일 때만 예약권 삭제. KEYS[1]=token / ARGV[1]=userId
-- 반환: 1=삭제됨, 0=소유자 아님/없음
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
