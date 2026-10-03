if redis.call('EXISTS', KEYS[1]) == 0 then
	return 0
end
if not redis.call('SET', KEYS[3], '1', 'NX', 'EX', ARGV[1]) then
	return 0
end
redis.call('INCR', KEYS[1])
return 1
