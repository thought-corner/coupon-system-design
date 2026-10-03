if redis.call('EXISTS', KEYS[1]) == 0 then
	return -2
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
	return -1
end
if tonumber(redis.call('GET', KEYS[1])) <= 0 then
	return 0
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
