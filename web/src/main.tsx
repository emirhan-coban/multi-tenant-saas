import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { AuthProvider } from './store/auth';
import { CustomerAuthProvider } from './store/customer-auth';
import { AppRoutes } from './router/AppRoutes';
import { createQueryClient } from './query/client';
import './styles.css';

const queryClient = createQueryClient();
const router = createBrowserRouter([{ path: '*', element: <AppRoutes /> }]);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <CustomerAuthProvider>
          <RouterProvider router={router} />
        </CustomerAuthProvider>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
);
