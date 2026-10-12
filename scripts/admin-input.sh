#!/usr/bin/env bash
# 由创建/重置脚本 source；与 Java 后端使用完全相同的输入校验规则。
prepare_admin_cli() {
  local jar='apps/api/target/library-api-0.0.1-SNAPSHOT.jar'
  local validator='apps/api/target/classes/com/example/library/common/AdminCredentials.class'
  if [[ ! -f "$jar" || ! -f "$validator" ]] || [[ -n "$(find apps/api/src/main apps/api/pom.xml -type f -newer "$jar" -print -quit)" ]]; then
    echo '正在构建管理员工具……'
    (cd apps/api && ./mvnw -q -DskipTests package) || return 1
  fi
}

read_admin_credentials() {
  local username_prompt="$1"
  local confirmation=''
  while true; do
    if ! IFS= read -r -p "$username_prompt" ADMIN_USERNAME; then
      echo '输入已取消。' >&2
      return 1
    fi
    if ADMIN_USERNAME="$ADMIN_USERNAME" java -cp apps/api/target/classes com.example.library.common.AdminCredentials username; then
      break
    fi
  done
  while true; do
    if ! IFS= read -r -s -p '管理员密码（5～128 字符，不回显）：' ADMIN_PASSWORD; then
      echo >&2
      echo '输入已取消。' >&2
      return 1
    fi
    echo
    if ! ADMIN_USERNAME="$ADMIN_USERNAME" ADMIN_PASSWORD="$ADMIN_PASSWORD" java -cp apps/api/target/classes com.example.library.common.AdminCredentials; then
      unset ADMIN_PASSWORD
      continue
    fi
    if ! IFS= read -r -s -p '再次输入密码（不回显）：' confirmation; then
      echo >&2
      echo '输入已取消。' >&2
      return 1
    fi
    echo
    if [[ "$ADMIN_PASSWORD" != "$confirmation" ]]; then
      echo '两次密码不一致，请重新设置。' >&2
      unset ADMIN_PASSWORD
      confirmation=''
      continue
    fi
    confirmation=''
    export ADMIN_USERNAME ADMIN_PASSWORD
    return 0
  done
}
