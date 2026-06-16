import axios from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080'

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Request interceptor
api.interceptors.request.use(
  (config) => {
    // Token is set in auth store
    return config
  },
  (error) => Promise.reject(error)
)

// Response interceptor
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config
    
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true
      
      // Try to refresh token
      const storedRefresh = localStorage.getItem('stokr-auth')
      if (storedRefresh) {
        try {
          const parsed = JSON.parse(storedRefresh)
          const response = await axios.post(`${API_BASE_URL}/api/v1/auth/refresh`, {
            refreshToken: parsed.state.refreshToken
          })
          
          const { accessToken, refreshToken } = response.data
          api.defaults.headers.common['Authorization'] = `Bearer ${accessToken}`
          
          // Update localStorage
          const newState = {
            ...parsed,
            state: {
              ...parsed.state,
              accessToken,
              refreshToken,
            }
          }
          localStorage.setItem('stokr-auth', JSON.stringify(newState))
          
          // Retry original request
          originalRequest.headers['Authorization'] = `Bearer ${accessToken}`
          return api(originalRequest)
        } catch (refreshError) {
          // Refresh failed, logout
          localStorage.removeItem('stokr-auth')
          window.location.href = '/login'
          return Promise.reject(refreshError)
        }
      }
    }
    
    return Promise.reject(error)
  }
)

export default api
