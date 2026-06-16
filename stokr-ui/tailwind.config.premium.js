/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        bg: {
          primary: '#0a0e27',
          secondary: '#11152d',
          tertiary: '#1a2042',
          quaternary: '#222d4d'
        },
        accent: {
          blue: '#00d9ff',
          emerald: '#00ff9f',
          red: '#ff3860',
          purple: '#c77dff',
          gold: '#ffd700'
        },
        text: {
          primary: '#ffffff',
          secondary: '#a8b5c8',
          tertiary: '#6b7b94'
        },
        status: {
          success: '#00ff9f',
          warning: '#c77dff',
          critical: '#ff3860',
          info: '#00d9ff',
          neutral: '#6b7b94'
        }
      },
      spacing: {
        0: '0',
        1: '4px',
        2: '8px',
        3: '12px',
        4: '16px',
        5: '20px',
        6: '24px',
        8: '32px',
        10: '40px',
        12: '48px',
        16: '64px'
      },
      fontSize: {
        xs: ['12px', { lineHeight: '1.4', letterSpacing: '0.1px' }],
        sm: ['14px', { lineHeight: '1.5', letterSpacing: '0.2px' }],
        base: ['16px', { lineHeight: '1.5', letterSpacing: '0.3px' }],
        lg: ['18px', { lineHeight: '1.4', letterSpacing: '0.4px' }],
        xl: ['20px', { lineHeight: '1.3', letterSpacing: '0.5px' }],
        '2xl': ['24px', { lineHeight: '1.3', letterSpacing: '0.6px' }],
        '3xl': ['30px', { lineHeight: '1.2', letterSpacing: '0.7px' }],
        '4xl': ['36px', { lineHeight: '1.2', letterSpacing: '-0.5px' }],
        '5xl': ['48px', { lineHeight: '1.1', letterSpacing: '-1px' }]
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace']
      },
      fontWeight: {
        thin: '100',
        extralight: '200',
        light: '300',
        normal: '400',
        medium: '500',
        semibold: '600',
        bold: '700',
        extrabold: '800',
        black: '900'
      },
      borderRadius: {
        none: '0',
        sm: '4px',
        md: '8px',
        lg: '12px',
        xl: '16px',
        full: '9999px'
      },
      boxShadow: {
        none: 'none',
        sm: '0 2px 4px rgba(0, 0, 0, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.1)',
        md: '0 4px 12px rgba(0, 0, 0, 0.2), inset 0 1px 0 rgba(255, 255, 255, 0.15)',
        lg: '0 8px 24px rgba(0, 0, 0, 0.3), inset 0 1px 0 rgba(255, 255, 255, 0.2)',
        xl: '0 16px 48px rgba(0, 0, 0, 0.4), inset 0 1px 0 rgba(255, 255, 255, 0.25)',
        glow: '0 0 20px rgba(0, 217, 255, 0.3)',
        'glow-emerald': '0 0 20px rgba(0, 255, 159, 0.3)',
        'glow-red': '0 0 20px rgba(255, 56, 96, 0.3)',
        'glow-purple': '0 0 20px rgba(199, 125, 255, 0.3)',
        'inner': 'inset 0 2px 4px rgba(0, 0, 0, 0.05)'
      },
      backdropBlur: {
        xs: 'blur(4px)',
        sm: 'blur(8px)',
        md: 'blur(12px)',
        lg: 'blur(16px)',
        xl: 'blur(20px)',
        '2xl': 'blur(24px)'
      },
      animation: {
        pulse: 'pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite',
        shimmer: 'shimmer 2s infinite',
        'glow-pulse': 'glow-pulse 2s ease-in-out infinite',
        float: 'float 3s ease-in-out infinite'
      },
      keyframes: {
        pulse: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '.7' }
        },
        shimmer: {
          '0%': { backgroundPosition: '0% 0%' },
          '100%': { backgroundPosition: '100% 0%' }
        },
        'glow-pulse': {
          '0%, 100%': { boxShadow: '0 0 20px rgba(0, 217, 255, 0.3)' },
          '50%': { boxShadow: '0 0 40px rgba(0, 217, 255, 0.5)' }
        },
        float: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-10px)' }
        }
      },
      transitionDuration: {
        0: '0ms',
        75: '75ms',
        100: '100ms',
        150: '150ms',
        200: '200ms',
        300: '300ms',
        500: '500ms',
        700: '700ms',
        1000: '1000ms'
      },
      transitionTimingFunction: {
        'spring-light': 'cubic-bezier(0.34, 1.56, 0.64, 1)',
        'spring-normal': 'cubic-bezier(0.25, 1.46, 0.45, 0.94)',
        'spring-stiff': 'cubic-bezier(0.36, 0, 0.66, -0.56)'
      }
    }
  },
  plugins: [
    require('@tailwindcss/forms'),
    require('@tailwindcss/typography'),
    // Custom plugin for glassmorphism utilities
    function ({ addUtilities }) {
      const glassmorphism = {
        '.glass': {
          'backdrop-filter': 'blur(20px)',
          'background-color': 'rgba(17, 21, 45, 0.4)',
          'border': '1px solid rgba(0, 217, 255, 0.1)'
        },
        '.glass-hover': {
          '@apply transition-all duration-300': {},
          '&:hover': {
            'backdrop-filter': 'blur(24px)',
            'background-color': 'rgba(17, 21, 45, 0.6)',
            'border-color': 'rgba(0, 217, 255, 0.3)'
          }
        }
      };
      addUtilities(glassmorphism);
    }
  ]
};
