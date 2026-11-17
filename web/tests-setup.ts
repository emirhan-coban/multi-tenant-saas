// Yavaş CI çalıştırıcılarında findBy*/waitFor varsayılan 1 sn'yi aşıp testi kararsız yapıyordu.
import { configure } from '@testing-library/react';

configure({ asyncUtilTimeout: 5000 });
