import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick } from 'vue'

const dependenciesKey = '__savedEvidenceScreenTest'
const moduleUrl = (source) => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`

// 실제 화면과 분석 API를 사용하고 통신·인증·기본 입력 요소만 대체한다.
const primitive = (name) => ({
    name,
    inheritAttrs: false,
    props: ['modelValue', 'label', 'disabled', 'loading', 'type', 'options', 'rows', 'columns', 'emptyText'],
    emits: ['update:modelValue'],
    setup(props, { attrs, slots, emit }) {
        if (name === 'BaseButton') return () => h('button', { ...attrs, disabled: Boolean(props.disabled || props.loading) }, slots.default?.())
        if (name === 'BaseTable') {
            return () =>
                h('table', {}, [
                    ...(props.rows || []).map((row) =>
                        h(
                            'tr',
                            { key: row.id },
                            props.columns.map((column) =>
                                h('td', {}, slots[`cell-${column.key}`]?.({ row, value: row[column.key] }) || String(row[column.key] ?? ''))
                            )
                        )
                    ),
                    slots.footer?.(),
                ])
        }
        const element = name === 'BaseSelect' ? 'select' : name === 'BaseTextarea' ? 'textarea' : 'input'
        return () =>
            h('label', {}, [
                props.label,
                h(
                    element,
                    {
                        ...attrs,
                        'data-label': props.label,
                        value: props.modelValue,
                        disabled: props.disabled,
                        type: props.type,
                        onChange: (event) => emit('update:modelValue', event.target.value),
                        onInput: (event) => emit('update:modelValue', event.target.value),
                    },
                    element === 'select' ? props.options?.map((option) => h('option', { value: option.value }, option.label)) : undefined
                ),
            ])
    },
})

globalThis[dependenciesKey] = {
    primitives: Object.fromEntries(
        ['BaseButton', 'BaseInput', 'BaseSelect', 'BaseTextarea', 'BaseTable', 'BasePagination', 'BaseDatePicker'].map((name) => [name, primitive(name)])
    ),
}
const httpModule = moduleUrl(`export const httpClient = { post: (...args) => globalThis.${dependenciesKey}.api.post(...args) }`)
const workflowUrl = new URL('../src/lib/requirementWorkflow.js', import.meta.url).href
const analysisApiSource = (await readFile(new URL('../src/api/analysisApi.js', import.meta.url), 'utf8'))
    .replace("'@/api/httpClient'", JSON.stringify(httpModule))
    .replace("'@/lib/requirementWorkflow'", JSON.stringify(workflowUrl))
    .replace('import.meta.env.VITE_USE_MOCK', "'false'")
const analysisApiModule = moduleUrl(analysisApiSource)
const apiModule = (names) => moduleUrl(names.map((name) => `export const ${name} = (...args) => globalThis.${dependenciesKey}.api.${name}(...args)`).join('\n'))
const authModule = moduleUrl(`export const useAuthStore = () => ({ hasPermission: () => true })`)
const routerModule = moduleUrl(`export const useRouter = () => globalThis.${dependenciesKey}.router`)
const compiled = new Map()
const compileComponent = async (path) => {
    if (compiled.has(path.href)) return compiled.get(path.href)
    const source = await readFile(path, 'utf8')
    let code = compileScript(parse(source).descriptor, {
        id: path.pathname,
        inlineTemplate: true,
        templateOptions: { compilerOptions: { hoistStatic: false } },
    }).content
    for (const match of [...code.matchAll(/from\s+(['"])([^'"]+)\1/g)]) {
        const specifier = match[2]
        let dependency
        if (specifier === '@/api/analysisApi') dependency = analysisApiModule
        else if (specifier === '@/api/appApi') dependency = apiModule(['getAnalysisHistory'])
        else if (specifier === '@/api/projectApi') dependency = apiModule(['getProjects'])
        else if (specifier === '@/api/requirementApi') dependency = apiModule(['getAllRequirements', 'createProjectRequirement'])
        else if (specifier === '@/stores/authStore') dependency = authModule
        else if (specifier === 'vue-router') dependency = routerModule
        else if (specifier.startsWith('@/components/base/')) {
            const name = specifier.split('/').at(-1).replace('.vue', '')
            dependency = moduleUrl(`export default globalThis.${dependenciesKey}.primitives.${name}`)
        } else if (specifier.startsWith('@/') && specifier.endsWith('.vue'))
            dependency = await compileComponent(new URL(`../src/${specifier.slice(2)}`, import.meta.url))
        else if (specifier.startsWith('@/')) dependency = new URL(`../src/${specifier.slice(2)}.js`, import.meta.url).href
        else dependency = import.meta.resolve(specifier)
        code = code.replaceAll(match[0], `from ${JSON.stringify(dependency)}`)
    }
    const result = moduleUrl(code)
    compiled.set(path.href, result)
    return result
}
const { default: Screen } = await import(await compileComponent(new URL('../src/components/requirement/LiveRequirementAnalysis.vue', import.meta.url)))

const makeNode = (type, text = '') => ({
    type,
    text,
    children: [],
    props: {},
    parent: null,
    style: {},
    value: '',
    checked: false,
    listeners: new Map(),
    scrollIntoView() {},
    addEventListener(name, callback) {
        this.listeners.set(name, [...(this.listeners.get(name) || []), callback])
    },
    removeEventListener(name, callback) {
        this.listeners.set(
            name,
            (this.listeners.get(name) || []).filter((item) => item !== callback)
        )
    },
})
const deferred = () => {
    let resolve
    let reject
    const promise = new Promise((done, fail) => {
        resolve = done
        reject = fail
    })
    return { promise, resolve, reject }
}
const requirement = (number) => ({
    id: `requirement-${number}`,
    projectId: `project-${number}`,
    requirementKey: `REQ-${number}`,
    title: `변경요청 ${number}`,
    status: 'READY',
    priority: 'MEDIUM',
    createdAt: '2026-11-03T01:00:00Z',
    currentVersion: { id: `version-${number}`, versionNumber: 1 },
})
const snapshot = (id, warnings = []) => ({
    jobId: id,
    status: 'COMPLETED',
    createdAt: '2026-11-01T01:00:00Z',
    sourceCommit: 'a'.repeat(40),
    requirementTitle: `저장 기준 ${id}`,
    reusableEvidence: true,
    evidenceWarnings: warnings,
})
const page = (items, total = items.length) => ({ items, total, page: 0, size: 20 })

const mountScreen = async ({ history = async () => page([]), requirements = [requirement(1)] } = {}) => {
    const historyCalls = []
    const posts = []
    const routes = []
    globalThis[dependenciesKey].api = {
        getProjects: async () => requirements.map((item) => ({ id: item.projectId, name: item.projectId, sourceType: 'GIT' })),
        getAllRequirements: async () => requirements,
        getAnalysisHistory: async (options) => {
            historyCalls.push(structuredClone(options))
            return history(options)
        },
        post: async (...args) => {
            posts.push(structuredClone(args))
            return { data: { jobId: 'created-analysis' } }
        },
        createProjectRequirement: async () => {
            throw new Error('이 검사에서는 요구사항을 만들지 않습니다.')
        },
    }
    globalThis[dependenciesKey].router = { push: async (route) => routes.push(route) }
    const detach = (node) => {
        if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
        node.parent = null
    }
    const renderer = createRenderer({
        createElement: makeNode,
        createText: (text) => makeNode('text', text),
        createComment: (text) => makeNode('comment', text),
        setText: (node, text) => (node.text = text),
        setElementText: (node, text) => {
            node.text = text
            node.children = []
        },
        patchProp(node, key, previous, value) {
            node.props[key] = value
            if (key === 'value') {
                node.value = value
                node._value = value
            }
            if (key === 'checked') node.checked = value
            if (key === 'type') node.typeName = value
        },
        parentNode: (node) => node.parent,
        nextSibling: (node) => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
        insert(node, parent, anchor = null) {
            detach(node)
            const index = anchor ? parent.children.indexOf(anchor) : -1
            parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
            node.parent = parent
        },
        remove: detach,
    })
    const root = makeNode('root')
    const app = renderer.createApp(Screen)
    app.component('RouterLink', {
        setup:
            (_, { slots }) =>
            () =>
                h('a', {}, slots.default?.()),
    })
    app.mount(root)
    const settle = async () => {
        for (let step = 0; step < 8; step++) {
            await Promise.resolve()
            await nextTick()
        }
    }
    const all = (node = root) => [node, ...node.children.flatMap((child) => all(child))]
    const text = (node = root) => (node.type === 'comment' ? '' : `${node.text} ${node.children.map((child) => text(child)).join(' ')}`)
    const find = (predicate) => {
        const node = all().find(predicate)
        assert.ok(node, '화면 요소가 있어야 합니다.')
        return node
    }
    const button = (label) => find((node) => node.type === 'button' && text(node).trim().replace(/\s+/g, ' ') === label)
    const fire = async (node, name, changes = {}) => {
        Object.assign(node, changes)
        const event = { target: node }
        for (const callback of node.listeners.get(name) || []) callback(event)
        const handler = node.props[`on${name[0].toUpperCase()}${name.slice(1)}`]
        if (Array.isArray(handler)) for (const callback of handler) callback(event)
        else handler?.(event)
        await settle()
    }
    const radio = (value) => find((node) => node.type === 'input' && node.props.value === value)
    const start = async (number = 1) => {
        const row = find((node) => node.type === 'tr' && text(node).includes(`REQ-${number}`))
        const node = all(row).find((item) => item.type === 'button' && text(item).trim() === '새 분석')
        assert.ok(node)
        await fire(node, 'click')
    }
    await settle()
    return { all, text, find, button, radio, fire, start, settle, historyCalls, posts, routes, cleanup: () => app.unmount() }
}

const chooseSaved = (screen) => screen.fire(screen.radio('SAVED'), 'change', { checked: true })
const savedSelect = (screen) => screen.find((node) => node.type === 'select' && node.props['data-label'] === '분석 기준 시점 · Git 커밋')
const savedConfirmation = (screen) =>
    screen.find((node) => node.type === 'input' && node.props.type === 'checkbox' && screen.text(node.parent).includes('선택한 과거 시점'))

test('저장 목록이 없어도 라디오를 선택할 수 있고 선택 후 20개 단위 전용 조회를 시작한다', async () => {
    const pending = deferred()
    const screen = await mountScreen({ history: () => pending.promise })
    try {
        await screen.start()
        assert.equal(Boolean(screen.radio('SAVED').props.disabled), false)
        assert.equal(screen.historyCalls.length, 0)
        await chooseSaved(screen)
        assert.equal(screen.radio('SAVED').checked, true)
        assert.deepEqual(screen.historyCalls, [{ projectId: 'project-1', page: 0, size: 20, reusableEvidenceOnly: true }])
        assert.match(screen.text(), /저장된 분석 기준을 불러오는 중/)
        assert.equal(screen.button('분석 시작').props.disabled, true)
        pending.resolve(page([]))
        await screen.settle()
        assert.equal(screen.radio('SAVED').checked, true)
        assert.match(screen.text(), /선택할 수 있는 저장 근거가 없습니다/)
        assert.equal(screen.button('분석 시작').props.disabled, true)
        await screen.fire(screen.button('분석 시작'), 'click')
        assert.equal(screen.posts.length, 0)
        assert.equal(screen.radio('SAVED').checked, true)
        await screen.fire(screen.button('최신 Git·DB 확인으로 분석하기'), 'click')
        assert.equal(screen.radio('VERIFY_CURRENT').checked, true)
    } finally {
        screen.cleanup()
    }
})

test('부분 자료의 경고를 표시하고 과거 시점 사용을 확인한 선택 기준만 분석 API에 보낸다', async () => {
    const screen = await mountScreen({
        history: async () => page([snapshot('recent'), snapshot('partial', ['SOURCE_COLLECTOR_OUTDATED', 'DATABASE_TRUNCATED'])]),
    })
    try {
        await screen.start()
        await chooseSaved(screen)
        await screen.fire(savedSelect(screen), 'change', { value: 'partial' })
        assert.match(screen.text(), /이전 수집 방식/)
        assert.match(screen.text(), /DB 구조 일부만 수집/)
        assert.equal(screen.button('분석 시작').props.disabled, true)
        await screen.fire(savedConfirmation(screen), 'change', { checked: true })
        assert.equal(screen.button('분석 시작').props.disabled, false)
        await screen.fire(screen.button('분석 시작'), 'click')
        assert.deepEqual(screen.posts, [
            [
                '/projects/project-1/analysis-jobs',
                {
                    thinkingEnabled: true,
                    thinkingEffort: 'medium',
                    requirementVersionId: 'version-1',
                    reuseFromJobId: 'partial',
                    confirmedUnchanged: true,
                },
            ],
        ])
        assert.deepEqual(screen.routes, [{ name: 'analysis-progress', params: { projectId: 'project-1', analysisJobId: 'created-analysis' } }])
    } finally {
        screen.cleanup()
    }
})

test('초기 조회 실패는 저장 방식을 유지하고 재시도한 뒤에만 분석 기준을 고를 수 있다', async () => {
    let count = 0
    const screen = await mountScreen({
        history: async () => {
            if (count++ === 0) throw { response: { data: { message: '저장소 연결을 확인해 주세요.' } } }
            return page([snapshot('retried')])
        },
    })
    try {
        await screen.start()
        await chooseSaved(screen)
        assert.match(screen.text(), /저장소 연결을 확인해 주세요/)
        assert.equal(screen.radio('SAVED').checked, true)
        await screen.fire(screen.button('분석 시작'), 'click')
        assert.equal(screen.posts.length, 0)
        await screen.fire(screen.button('조회 다시 시도'), 'click')
        assert.equal(savedSelect(screen).props.value, 'retried')
        assert.equal(screen.historyCalls.length, 2)
        assert.equal(screen.radio('SAVED').checked, true)
    } finally {
        screen.cleanup()
    }
})

test('더보기는 다음 페이지를 합치고 기준을 바꾸면 과거 시점 확인을 다시 받는다', async () => {
    const first = Array.from({ length: 20 }, (_, index) => snapshot(`saved-${index}`))
    const screen = await mountScreen({ history: async ({ page: number }) => (number === 0 ? page(first, 21) : page([snapshot('older')], 21)) })
    try {
        await screen.start()
        await chooseSaved(screen)
        await screen.fire(savedConfirmation(screen), 'change', { checked: true })
        assert.equal(screen.button('분석 시작').props.disabled, false)
        await screen.fire(screen.button('이전 기준 더 보기'), 'click')
        assert.deepEqual(
            screen.historyCalls.map((item) => item.page),
            [0, 1]
        )
        assert.equal(savedSelect(screen).children.filter((node) => node.type === 'option').length, 21)
        assert.equal(screen.button('분석 시작').props.disabled, false)
        await screen.fire(savedSelect(screen), 'change', { value: 'older' })
        assert.equal(savedConfirmation(screen).checked, false)
        assert.equal(screen.button('분석 시작').props.disabled, true)
        assert.equal(screen.posts.length, 0)
    } finally {
        screen.cleanup()
    }
})

test('이전 프로젝트의 늦은 조회 응답은 새 분석 기준 목록을 덮어쓰지 않는다', async () => {
    const old = deferred()
    const screen = await mountScreen({
        requirements: [requirement(1), requirement(2)],
        history: ({ projectId }) => (projectId === 'project-1' ? old.promise : Promise.resolve(page([snapshot('second-project')]))),
    })
    try {
        await screen.start(1)
        await chooseSaved(screen)
        await screen.start(2)
        await chooseSaved(screen)
        assert.equal(savedSelect(screen).props.value, 'second-project')
        old.resolve(page([snapshot('late-first-project')]))
        await screen.settle()
        assert.equal(savedSelect(screen).props.value, 'second-project')
        assert.doesNotMatch(screen.text(), /late-first-project/)
        assert.deepEqual(
            screen.historyCalls.map((item) => item.projectId),
            ['project-1', 'project-2']
        )
        assert.equal(screen.radio('SAVED').checked, true)
    } finally {
        screen.cleanup()
    }
})

test('조회 중 이력이 추가되어 다음 페이지가 겹쳐도 마지막 페이지에서 더보기를 끝낸다', async () => {
    const first = Array.from({ length: 20 }, (_, index) => snapshot(`saved-${index}`))
    const screen = await mountScreen({
        history: async ({ page: number }) => (number === 0 ? page(first, 21) : page([first.at(-1), snapshot('older')], 22)),
    })
    try {
        await screen.start()
        await chooseSaved(screen)
        await screen.fire(screen.button('이전 기준 더 보기'), 'click')
        assert.equal(savedSelect(screen).children.filter((node) => node.type === 'option').length, 21)
        assert.doesNotMatch(screen.text(), /이전 기준 더 보기/)
        assert.match(screen.text(), /22건 중 21건 표시/)
        assert.deepEqual(
            screen.historyCalls.map((item) => item.page),
            [0, 1]
        )
    } finally {
        screen.cleanup()
    }
})
