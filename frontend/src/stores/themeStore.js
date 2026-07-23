import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

const STORAGE_KEY = 'doenaai-theme'

const getInitialTheme = () => {
    const savedTheme = localStorage.getItem(STORAGE_KEY)

    if (savedTheme === 'light' || savedTheme === 'dark') {
        return savedTheme
    }

    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export const useThemeStore = defineStore('theme', () => {
    const theme = ref('light')
    const isDark = computed(() => theme.value === 'dark')

    const applyTheme = (nextTheme, withTransition = true) => {
        const html = document.documentElement

        if (withTransition) {
            html.classList.add('theme-transition')
        }

        theme.value = nextTheme
        html.classList.toggle('dark', nextTheme === 'dark')
        localStorage.setItem(STORAGE_KEY, nextTheme)

        if (withTransition) {
            window.setTimeout(() => {
                html.classList.remove('theme-transition')
            }, 220)
        }
    }

    const initializeTheme = () => {
        applyTheme(getInitialTheme(), false)
    }

    const toggleTheme = () => {
        applyTheme(isDark.value ? 'light' : 'dark')
    }

    return {
        theme,
        isDark,
        initializeTheme,
        toggleTheme,
        applyTheme,
    }
})
