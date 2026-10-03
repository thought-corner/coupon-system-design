if redis.call('EXISTS', KEYS[1]) == 1 then
	return 0
end
redis.call('DEL', KEYS[2])
for i = 2, #ARGV, 1000 do
	redis.call('SADD', KEYS[2], unpack(ARGV, i, math.min(i + 999, #ARGV)))
end
redis.call('SET', KEYS[1], ARGV[1])
return 1
