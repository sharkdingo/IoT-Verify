// src/api/auth.ts - 认证API
import api from './http';
import { CREDENTIAL_LIMITS } from '@/constants/requestLimits';
import { isValidNormalizedUsername, normalizeAccountIdentifier } from '@/utils/accountIdentifier';
import type { 
  LoginRequest, 
  LoginResponse, 
  RegisterRequest, 
  RegisterResponse,
  DeleteAccountRequest,
  AccountProfile,
  UpdateUsernameRequest,
  ChangePasswordRequest,
  UserInfo,
  Result 
} from '../types/auth';

const hasValidAccountIdentity = (data: UserInfo): boolean =>
  Number.isSafeInteger(data.userId) && data.userId > 0
    && typeof data.phone === 'string' && CREDENTIAL_LIMITS.phonePattern.test(data.phone)
    && typeof data.username === 'string' && isValidNormalizedUsername(data.username)
    && normalizeAccountIdentifier(data.username) === data.username
    && !CREDENTIAL_LIMITS.phonePattern.test(data.username);

const validateAuthResponse = (body: Result<LoginResponse>): Result<LoginResponse> => {
  if (!body || !Number.isInteger(body.code)) throw new Error('Authentication response is incomplete');
  if (body.code === 200) {
    const data = body.data;
    if (!data || !hasValidAccountIdentity(data) || typeof data.token !== 'string'
        || !data.token.trim() || data.token !== data.token.trim()) {
      throw new Error('Authentication response is incomplete');
    }
  }
  return body;
};

const validateAccountResponse = (body: Result<AccountProfile>): Result<AccountProfile> => {
  const data = body?.data;
  if (body?.code !== 200 || !data || !hasValidAccountIdentity(data)
      || typeof data.createdAt !== 'string'
      || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(data.createdAt)
      || !Number.isFinite(Date.parse(data.createdAt))) {
    throw new Error('Account response is incomplete');
  }
  return body;
};

export const authApi = {
  async getAccount(authToken: string): Promise<Result<AccountProfile>> {
    const response = await api.get<Result<AccountProfile>>('/auth/account', {
      headers: { Authorization: `Bearer ${authToken}` }
    });
    return validateAccountResponse(response.data);
  },

  async updateUsername(data: UpdateUsernameRequest, authToken: string): Promise<Result<AccountProfile>> {
    const response = await api.patch<Result<AccountProfile>>('/auth/account/username', data, {
      headers: { Authorization: `Bearer ${authToken}` }
    });
    return validateAccountResponse(response.data);
  },

  async changePassword(data: ChangePasswordRequest, authToken: string): Promise<Result<void>> {
    const response = await api.post<Result<void>>('/auth/account/password', data, {
      headers: { Authorization: `Bearer ${authToken}` }
    });
    return response.data;
  },

  // 登录
  async login(data: LoginRequest): Promise<Result<LoginResponse>> {
    const response = await api.post<Result<LoginResponse>>('/auth/login', data);
    return validateAuthResponse(response.data);
  },

  // 注册
  async register(data: RegisterRequest): Promise<Result<RegisterResponse>> {
    const response = await api.post<Result<RegisterResponse>>('/auth/register', data);
    return validateAuthResponse(response.data);
  },

  // 登出
  async logout(authToken: string): Promise<Result<void>> {
    const response = await api.post<Result<void>>('/auth/logout', undefined, {
      headers: { Authorization: `Bearer ${authToken}` }
    });
    return response.data;
  },

  // 注销账号
  async deleteAccount(data: DeleteAccountRequest, authToken: string): Promise<Result<void>> {
    const response = await api.delete<Result<void>>('/auth/account', {
      data,
      headers: { Authorization: `Bearer ${authToken}` }
    });
    return response.data;
  }
};
