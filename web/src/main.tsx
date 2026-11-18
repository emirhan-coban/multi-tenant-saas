// Yazı tipleri kendi paketimizden sunulur (Google Fonts'a istek yok: gizlilik/KVKK, CSP ve çevrimdışı PWA).
// Her dosya latin + latin-ext (Türkçe karakterler) alt kümelerini unicode-range ile ihtiyaç anında yükler.
import '@fontsource/dm-sans/400.css';
import '@fontsource/dm-sans/500.css';
import '@fontsource/dm-sans/600.css';
import '@fontsource/dm-sans/700.css';
import '@fontsource/space-grotesk/500.css';
import '@fontsource/space-grotesk/600.css';
import '@fontsource/space-grotesk/700.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { AuthProvider } from './store/auth';
import { CustomerAuthProvider } from './store/customer-auth';
import { AppRoutes } from './router/AppRoutes';
import { createQueryClient } from './query/client';
import './styles.css';
import { registerPwa } from './pwa/register';
import { UpdateBanner } from './components/UpdateBanner';

registerPwa();

const queryClient = createQueryClient();
const router = createBrowserRouter([{ path: '*', element: <AppRoutes /> }]);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <CustomerAuthProvider>
          <RouterProvider router={router} />
          <UpdateBanner />
        </CustomerAuthProvider>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
);
