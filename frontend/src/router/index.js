import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '@/layouts/AdminLayout.vue'
import AuthLayout from '@/layouts/AuthLayout.vue'
import DashboardView from '@/views/DashboardView.vue'
import ProjectManagementView from '@/views/ProjectManagementView.vue'
import ProjectRegisterView from '@/views/ProjectRegisterView.vue'
import ChangeRequestManagementView from '@/views/ChangeRequestManagementView.vue'
import AnalysisHistoryView from '@/views/AnalysisHistoryView.vue'
import AiModelManagementView from '@/views/AiModelManagementView.vue'
import DocumentManagementView from '@/views/DocumentManagementView.vue'
import SettingsView from '@/views/SettingsView.vue'
import AnalysisProgressView from '@/views/AnalysisProgressView.vue'
import ArchitectureView from '@/views/ArchitectureView.vue'
import ImpactAnalysisView from '@/views/ImpactAnalysisView.vue'
import LoginView from '@/views/LoginView.vue'
import ForbiddenView from '@/views/ForbiddenView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import { useAuthStore } from '@/stores/authStore'

const routes = [
    {
        path: '/login',
        component: AuthLayout,
        children: [
            {
                path: '',
                name: 'login',
                component: LoginView,
                meta: {
                    public: true,
                    title: '로그인',
                },
            },
        ],
    },
    {
        path: '/',
        component: AdminLayout,
        meta: {
            requiresAuth: true,
        },
        children: [
            {
                path: '',
                redirect: '/dashboard',
            },
            {
                path: 'dashboard',
                name: 'dashboard',
                component: DashboardView,
                meta: {
                    title: '대시보드',
                    breadcrumb: '대시보드',
                    permission: 'DASHBOARD_READ',
                },
            },
            {
                path: 'projects',
                name: 'projects',
                component: ProjectManagementView,
                meta: {
                    title: '프로젝트 관리',
                    breadcrumb: '프로젝트 관리',
                    permission: 'PROJECT_READ',
                },
            },
            {
                path: 'projects/new',
                name: 'project-register',
                component: ProjectRegisterView,
                meta: {
                    title: '프로젝트 등록 및 분석',
                    breadcrumb: '프로젝트 관리 > 프로젝트 등록',
                    permission: 'PROJECT_CREATE',
                },
            },
            {
                path: 'change-requests',
                name: 'change-requests',
                component: ChangeRequestManagementView,
                meta: {
                    title: '변경요청 관리',
                    breadcrumb: '변경요청 관리',
                    permission: 'CHANGE_REQUEST_READ',
                },
            },
            {
                path: 'analysis-history',
                name: 'analysis-history',
                component: AnalysisHistoryView,
                meta: {
                    title: '분석 이력',
                    breadcrumb: '분석 이력',
                    permission: 'ANALYSIS_READ',
                },
            },
            {
                path: 'models',
                name: 'models',
                component: AiModelManagementView,
                meta: {
                    title: 'AI 모델 관리',
                    breadcrumb: 'AI 모델 관리',
                    permission: 'MODEL_READ',
                },
            },
            {
                path: 'documents',
                name: 'documents',
                component: DocumentManagementView,
                meta: {
                    title: '문서 관리',
                    breadcrumb: '문서 관리',
                    permission: 'DOCUMENT_READ',
                },
            },
            {
                path: 'settings',
                name: 'settings',
                component: SettingsView,
                meta: {
                    title: '설정',
                    breadcrumb: '설정',
                    permission: 'SYSTEM_SETTING_READ',
                },
            },
            {
                path: 'projects/:projectId/analysis/:analysisJobId/progress',
                name: 'analysis-progress',
                component: AnalysisProgressView,
                meta: {
                    title: '분석 진행 상태',
                    breadcrumb: '프로젝트 관리 > 분석 진행 상태',
                    permission: 'ANALYSIS_READ',
                },
            },
            {
                path: 'analysis/progress',
                redirect: {
                    name: 'projects',
                },
            },
            {
                path: 'architecture',
                name: 'architecture',
                component: ArchitectureView,
                meta: {
                    title: '통합 아키텍처',
                    breadcrumb: '프로젝트 관리 > 통합 아키텍처',
                    permission: 'PROJECT_READ',
                },
            },
            {
                path: 'impact',
                name: 'impact-analysis',
                component: ImpactAnalysisView,
                meta: {
                    title: '변경 영향 분석',
                    breadcrumb: '변경요청 관리 > 영향 분석',
                    permission: 'ANALYSIS_READ',
                },
            },
            {
                path: 'forbidden',
                name: 'forbidden',
                component: ForbiddenView,
                meta: {
                    title: '접근 권한 없음',
                },
            },
        ],
    },
    {
        path: '/:pathMatch(.*)*',
        name: 'not-found',
        component: NotFoundView,
        meta: {
            public: true,
            title: '페이지 없음',
        },
    },
]

const router = createRouter({
    history: createWebHistory(),
    routes,
})

router.beforeEach(async (to) => {
    const authStore = useAuthStore()

    try {
        await authStore.initialize()
    } catch {
        if (to.name !== 'login') {
            return {
                name: 'login',
            }
        }
    }

    const requiresAuth = to.matched.some((record) => record.meta.requiresAuth)

    if (requiresAuth && !authStore.isAuthenticated) {
        return {
            name: 'login',
            query: {
                redirect: to.fullPath,
            },
        }
    }

    if (to.name === 'login' && authStore.isAuthenticated) {
        return {
            name: 'dashboard',
        }
    }

    const requiredPermission = to.meta.permission

    if (requiresAuth && requiredPermission && authStore.currentUser && !authStore.hasPermission(requiredPermission)) {
        return {
            name: 'forbidden',
        }
    }

    return true
})

router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} | doenaai` : 'doenaai'
})

export default router
