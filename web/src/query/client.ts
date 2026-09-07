import { QueryClient } from '@tanstack/react-query';
import axios from 'axios';

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        gcTime: 5 * 60_000,
        retry: (failureCount, error) => {
          if (axios.isCancel(error)) return false;
          const status = axios.isAxiosError(error) ? error.response?.status : undefined;
          return !(status && status >= 400 && status < 500) && failureCount < 2;
        },
      },
      mutations: { retry: false },
    },
  });
}

export function clearSessionQueries(client: QueryClient) {
  void client.cancelQueries();
  client.clear();
}
