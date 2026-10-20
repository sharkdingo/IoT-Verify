// src/types/auth.ts

// 登录请求
export interface LoginRequest {
  identifier: string;
  password: string;
}

// 注册请求
export interface RegisterRequest {
  phone: string;
  username: string;
  password: string;
}

// 注销账号请求
export interface DeleteAccountRequest {
  password: string;
  confirmation: string;
}

// 登录响应（包含token）
export interface LoginResponse {
  userId: number;
  phone: string;
  username: string;
  token: string;
}

export type RegisterResponse = LoginResponse;

// 当前用户信息
export interface UserInfo {
  userId: number;
  phone: string;
  username: string;
}

export interface AccountProfile extends UserInfo {
  createdAt: string;
}

export interface UpdateUsernameRequest {
  username: string;
  currentPassword: string;
  expectedUsername: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

// 全局Result响应包装
export interface Result<T> {
  code: number;
  message: string;
  data?: T | null;
}
