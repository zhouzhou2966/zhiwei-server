#!/usr/bin/env bash
#
# 重置全部演示券（id 前缀 90000000000000）的领取状态，便于反复测试领券流程。
#
# 为什么需要它：
#   领券走的是「Redis 原子扣减 → MQ 异步落库」。如果只清 MySQL 的 user_coupon / issue_num，
#   Redis 里的库存和用户领取计数仍停在上一轮的值，下次领取会被 Lua 判成
#   「超出领取数量」（返回码 4）或「优惠券已售罄」（返回码 2）。
#   所以 DB 和 Redis 必须一起重置。
#
# 两个刻意的设计：
#   1. 按 id 前缀匹配，不写死券列表 —— 以后加 007、008 不用改脚本。
#   2. total_num 一律不动，Redis 的库存从 DB 读回来回填。
#      早期版本把 total_num 写死成 100，结果 003（200 张）、006（50 张）会被它改错；
#      演示券的价值就在于「库存量各不相同」能验证售罄逻辑，不能被重置脚本抹平。
#
# 用法：
#   bash zhiwei-server/scripts/reset-demo-coupon.sh
#
# 只影响演示券，不碰其它数据。

set -euo pipefail

# 数据库 / Redis 凭据：取演示环境的弱口令做默认值，可用环境变量覆盖。
# 不写字面量的原因：这个脚本要进仓库，而应用配置走 nacos，仓库里本来不含这些凭据。
MYSQL_PASSWORD="${DEMO_MYSQL_PASSWORD:-123}"
REDIS_PASSWORD="${DEMO_REDIS_PASSWORD:-123321}"

VM_HOST="192.168.150.101"
SSH_KEY="$HOME/.ssh/id_ed25519_tjxt"
# 演示券 id 是「9 + 13 个 0 + 3 位编号」，即 900000000000001 ~ 900000000000999。
# 前缀取 13 位（到最后一个 0）才能覆盖两位数编号：写成 14 位时 900000000000010
# 的第 14 位是 1，匹配不上，新加的券会被漏掉。
ID_PREFIX="9000000000000"
DB="zw_promotion"

ssh_run() {
  ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no "root@${VM_HOST}" "$@"
}

echo "==> 重置 MySQL：issue_num 归零、清掉已领记录（total_num 保持原值）"
ssh_run "cat > /tmp/reset_demo_coupon.sql <<'SQL'
UPDATE coupon SET issue_num = 0 WHERE id LIKE '${ID_PREFIX}%';
DELETE FROM user_coupon WHERE coupon_id LIKE '${ID_PREFIX}%';
SQL
docker cp /tmp/reset_demo_coupon.sql mysql:/tmp/reset_demo_coupon.sql >/dev/null
docker exec mysql mysql -uroot -p${MYSQL_PASSWORD} --default-character-set=utf8mb4 ${DB} -e 'source /tmp/reset_demo_coupon.sql'" 2>/dev/null

echo "==> 重置 Redis：库存按 DB 的 total_num 回填、清用户计数与幂等键"
ssh_run "R='docker exec redis redis-cli -a ${REDIS_PASSWORD} --no-auth-warning'
docker exec mysql mysql -uroot -p${MYSQL_PASSWORD} -N -B ${DB} \
  -e \"SELECT id, total_num FROM coupon WHERE id LIKE '${ID_PREFIX}%' ORDER BY id\" |
while read -r id total; do
  [ -z \"\${id}\" ] && continue
  \$R HSET prs:coupon:\${id} totalNum \${total} >/dev/null
  \$R DEL prs:user:coupon:\${id} >/dev/null
  for k in \$(\$R --scan --pattern \"msg:dedup:coupon:*:\${id}\"); do
    \$R DEL \"\$k\" >/dev/null
  done
  echo \"  \${id} -> totalNum=\${total}\"
done" 2>/dev/null

echo "==> 校验"
ssh_run "R='docker exec redis redis-cli -a ${REDIS_PASSWORD} --no-auth-warning'
echo '--- MySQL ---'
docker exec mysql mysql -uroot -p${MYSQL_PASSWORD} -N -B ${DB} -e \"
  SELECT c.id, c.total_num, c.issue_num, IFNULL(u.cnt, 0) AS claimed
  FROM coupon c
  LEFT JOIN (SELECT coupon_id, COUNT(*) cnt FROM user_coupon GROUP BY coupon_id) u ON u.coupon_id = c.id
  WHERE c.id LIKE '${ID_PREFIX}%' ORDER BY c.id\"
echo '--- Redis ---'
docker exec mysql mysql -uroot -p${MYSQL_PASSWORD} -N -B ${DB} \
  -e \"SELECT id, total_num FROM coupon WHERE id LIKE '${ID_PREFIX}%' ORDER BY id\" |
while read -r id total; do
  [ -z \"\${id}\" ] && continue
  echo -n \"  \${id} redis=\$(\$R HGET prs:coupon:\${id} totalNum) expect=\${total} usercounter=\$(\$R EXISTS prs:user:coupon:\${id}) dedup=\"
  \$R --scan --pattern \"msg:dedup:coupon:*:\${id}\" | wc -l
done" 2>/dev/null

echo "==> 完成，可以重新领券了"
