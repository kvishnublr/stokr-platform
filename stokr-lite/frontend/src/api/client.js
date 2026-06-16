import axios from 'axios';

const client = axios.create({ baseURL: '/api' });

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

client.interceptors.response.use(
  (res) => res,
  async (error) => {
    if (error.response?.status === 401) {
      const refresh = localStorage.getItem('refreshToken');
      if (refresh && !error.config._retried) {
        error.config._retried = true;
        try {
          const { data } = await axios.post('/api/auth/refresh', { refreshToken: refresh });
          localStorage.setItem('token', data.token);
          localStorage.setItem('refreshToken', data.refreshToken);
          error.config.headers.Authorization = `Bearer ${data.token}`;
          return client(error.config);
        } catch {
          localStorage.clear();
          window.location.href = '/login';
        }
      } else {
        localStorage.clear();
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

export default client;
