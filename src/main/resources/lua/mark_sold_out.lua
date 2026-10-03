if redis.call('EXISTS', KEYS[1]) == 0 then
	return 0
end
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('SET', KEYS[1], '0')
return 1
