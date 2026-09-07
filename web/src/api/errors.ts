import axios from 'axios';
import type { ApiError } from './types';

export function errorMessage(error: unknown, fallback: string) {
  if (!axios.isAxiosError<ApiError>(error)) return fallback;
  if (error.response?.status === 403) return 'Bu işlem için yetkiniz bulunmuyor.';
  if (error.response?.status === 401) return 'Oturumunuz sona erdi. Lütfen yeniden giriş yapın.';
  if (!error.response) return 'Sunucuya ulaşılamadı. Bağlantınızı kontrol edip tekrar deneyin.';
  return error.response.status < 500 && typeof error.response.data?.message === 'string' ? error.response.data.message : fallback;
}
