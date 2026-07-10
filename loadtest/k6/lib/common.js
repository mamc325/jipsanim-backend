import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

export const SIGUNGU = ['11680', '11650', '11440', '11710'];

export function randomFrom(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

/** 회원가입(존재하면 무시) 후 로그인해 accessToken 반환. setup()에서 사용 권장. */
export function signupAndLogin(email, role, extra = {}) {
  const body = Object.assign(
    { email, password: 'password1', nickname: email.split('@')[0], role },
    extra,
  );
  http.post(`${BASE_URL}/api/auth/signup`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
  }); // 중복이면 400 — 무시
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ email, password: 'password1' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  return res.json('data.accessToken');
}

export function authHeaders(token) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}
