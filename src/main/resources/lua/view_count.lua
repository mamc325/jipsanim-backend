-- 조회수 카운트 원자 실행 (6차 P1): dedup + writeback 델타 + 트렌딩 랭킹을 한 번에.
-- 부분 성공(dedup 키만 생성 / HINCRBY 만 성공) 방지.
-- KEYS[1] = view:dedup:{propertyId}:{viewerKey}
-- KEYS[2] = view:pending (hash)
-- KEYS[3] = property:popular (zset)
-- ARGV[1] = dedup window seconds
-- ARGV[2] = propertyId
-- return 1 = counted, 0 = dedup 중복
if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
  redis.call('HINCRBY', KEYS[2], ARGV[2], 1)
  redis.call('ZINCRBY', KEYS[3], 1, ARGV[2])
  return 1
else
  return 0
end
