import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { runInNewContext } from 'node:vm'
import { createMemoryHistory, createRouter } from 'vue-router'

const readSource = (path) => readFileSync(new URL(path, import.meta.url), 'utf8')
const routerSource = readSource('../src/router/index.js')

const routeTable = () => {
    const components = [...routerSource.matchAll(/^import (\w+) from '@\/(?:views|layouts)\//gm)].map((match) => match[1])
    const context = Object.fromEntries(components.map((name) => [name, { name }]))
    const expression = routerSource.match(/const routes = (\[[\s\S]*?\n\])\n\nconst router/)[1]
    return createRouter({ history: createMemoryHistory(), routes: runInNewContext(expression, context) })
}

test('제출용 메뉴에는 핵심 다섯 화면만 남기고 기존 권한 조건을 유지한다', () => {
    const source = readSource('../src/components/layout/AppSidebar.vue')
    const expression = source.match(/const menus = (\[[\s\S]*?\n\])/)[1]
    const menus = JSON.parse(JSON.stringify(runInNewContext(expression)))
    assert.deepEqual(
        menus.map(({ path, permission }) => ({ path, permission })),
        [
            { path: '/dashboard', permission: 'DASHBOARD_READ' },
            { path: '/projects', permission: 'PROJECT_READ' },
            { path: '/change-requests', permission: 'CHANGE_REQUEST_READ' },
            { path: '/analysis-history', permission: 'ANALYSIS_READ' },
            { path: '/models', permission: 'MODEL_READ' },
        ]
    )
    assert.doesNotMatch(source, /문서 관리|\/documents|\/settings/)
    assert.match(source, /authStore\.hasPermission\(menu\.permission\)/)
})

test('제외한 화면의 이전 주소는 로그인 보호를 유지하면서 대시보드로 보낸다', () => {
    const router = routeTable()
    for (const path of ['/documents', '/settings']) {
        const resolved = router.resolve(path)
        assert.equal(resolved.matched.at(-1).redirect, '/dashboard')
        assert.ok(resolved.matched.some((record) => record.meta.requiresAuth))
    }
    assert.equal(router.hasRoute('documents'), false)
    assert.equal(router.hasRoute('settings'), false)
    assert.doesNotMatch(routerSource, /DocumentManagementView|SettingsView/)
    assert.equal(router.resolve('/dashboard').meta.permission, 'DASHBOARD_READ')
})

test('프로젝트·변경요청·분석 이력·모델과 상세 분석 경로는 그대로 유지한다', () => {
    const router = routeTable()
    const cases = [
        ['/projects', 'projects', 'PROJECT_READ'],
        ['/change-requests', 'change-requests', 'CHANGE_REQUEST_READ'],
        ['/analysis-history', 'analysis-history', 'ANALYSIS_READ'],
        ['/models', 'models', 'MODEL_READ'],
        ['/projects/project-example/analysis/job-example/progress', 'analysis-progress', 'ANALYSIS_READ'],
    ]
    for (const [path, name, permission] of cases) {
        const resolved = router.resolve(path)
        assert.equal(resolved.name, name)
        assert.equal(resolved.meta.permission, permission)
    }
})

test('대시보드는 예시 모드에서도 제외한 설정 화면으로 연결하지 않는다', () => {
    const source = readSource('../src/views/DashboardView.vue')
    assert.doesNotMatch(source, /\/settings|\/documents|연결 설정 관리/)
    assert.match(source, /authStore\.hasPermission\('PROJECT_READ'\)[^>]*to="\/projects"/)
})
