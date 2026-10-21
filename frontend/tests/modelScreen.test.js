import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick } from 'vue'

const dependenciesKey = '__doenaaiModelScreenTest'
const moduleUrl = (source) => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`

// 화면과 모델 모달은 원본을 컴파일하고, 통신·인증·기본 입력 요소만 시험용으로 대체한다.
const primitive = (name) => ({
    name,
    inheritAttrs: false,
    props: ['modelValue', 'label', 'disabled', 'loading', 'type', 'options', 'title', 'rows', 'columns', 'emptyText'],
    emits: ['update:modelValue'],
    setup(props, { attrs, slots, emit }) {
        if (name === 'BaseModal') {
            return () => (props.modelValue ? h('section', { role: 'dialog', 'aria-label': props.title }, slots.default?.()) : null)
        }
        if (name === 'BaseButton') {
            return () => h('button', { ...attrs, type: props.type || 'button', disabled: Boolean(props.disabled || props.loading) }, slots.default?.())
        }
        if (name === 'BaseTable') {
            return () =>
                h(
                    'table',
                    {},
                    props.rows?.length
                        ? props.rows.map((row) =>
                              h(
                                  'tr',
                                  { key: row.id },
                                  props.columns.map((column) =>
                                      h('td', {}, slots[`cell-${column.key}`]?.({ row, value: row[column.key] }) || String(row[column.key] ?? ''))
                                  )
                              )
                          )
                        : [h('tr', {}, props.emptyText || '')]
                )
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
                        onInput: (event) => emit('update:modelValue', event.target.value),
                        onChange: (event) => emit('update:modelValue', event.target.value),
                    },
                    element === 'select' ? props.options?.map((option) => h('option', { value: option.value }, option.label)) : undefined
                ),
            ])
    },
})

globalThis[dependenciesKey] = {
    primitives: Object.fromEntries(['BaseButton', 'BaseInput', 'BaseSelect', 'BaseTextarea', 'BaseModal', 'BaseTable'].map((name) => [name, primitive(name)])),
}

const apiModule = moduleUrl(
    [
        'getModelRegistry',
        'getModelRuntime',
        'getModelVersion',
        'registerModelVersion',
        'recordModelEvaluation',
        'getInstalledModels',
        'probeInstalledModel',
        'activateInstalledModel',
        'rollbackInstalledModel',
        'getModelTraining',
        'startModelTraining',
        'cancelModelTraining',
        'getModelOperations',
        'getModelOperation',
    ]
        .map((name) => `export const ${name} = (...args) => globalThis.${dependenciesKey}.api.${name}(...args)`)
        .join('\n')
)
const authModule = moduleUrl(`export const useAuthStore = () => globalThis.${dependenciesKey}.auth`)
const compiledModules = new Map()
const compileComponent = async (path) => {
    if (compiledModules.has(path.href)) return compiledModules.get(path.href)
    const source = await readFile(path, 'utf8')
    // DOM의 정적 HTML 삽입 최적화만 끄고 화면의 조건·이벤트·반응형 갱신은 그대로 검사한다.
    let code = compileScript(parse(source).descriptor, {
        id: path.pathname,
        inlineTemplate: true,
        templateOptions: { compilerOptions: { hoistStatic: false } },
    }).content
    for (const match of [...code.matchAll(/from\s+(['"])([^'"]+)\1/g)]) {
        const specifier = match[2]
        let dependency
        if (specifier === '@/api/modelApi') dependency = apiModule
        else if (specifier === '@/stores/authStore') dependency = authModule
        else if (specifier.startsWith('@/components/base/')) {
            const name = specifier.split('/').at(-1).replace('.vue', '')
            assert.ok(globalThis[dependenciesKey].primitives[name], `기본 요소 대체물이 필요합니다: ${name}`)
            dependency = moduleUrl(`export default globalThis.${dependenciesKey}.primitives.${name}`)
        } else if (specifier.startsWith('@/') && specifier.endsWith('.vue')) {
            dependency = await compileComponent(new URL(`../src/${specifier.slice(2)}`, import.meta.url))
        } else if (specifier.startsWith('@/')) dependency = new URL(`../src/${specifier.slice(2)}.js`, import.meta.url).href
        else dependency = import.meta.resolve(specifier)
        code = code.replaceAll(match[0], `from ${JSON.stringify(dependency)}`)
    }
    const result = moduleUrl(code)
    compiledModules.set(path.href, result)
    return result
}
const { default: ModelScreen } = await import(await compileComponent(new URL('../src/views/AiModelManagementView.vue', import.meta.url)))

const makeNode = (type, text = '') => ({ type, text, children: [], props: {}, parent: null, style: {}, addEventListener() {}, removeEventListener() {} })
const mountScreen = async (api, permissions = null) => {
    globalThis.document = { hidden: false, addEventListener() {}, removeEventListener() {} }
    globalThis[dependenciesKey].api = {
        getInstalledModels: async () => ({ model: null, currentRevision: 0, models: [], canRollback: false }),
        getModelTraining: async () => ({ capabilities: { trainingExecution: false }, jobs: [] }),
        getModelOperations: async () => ({ operations: [] }),
        ...api,
    }
    globalThis[dependenciesKey].auth = { hasPermission: (permission) => permissions === null || permissions.includes(permission) }
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
        patchProp: (node, key, previous, value) => (node.props[key] = value),
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
    const app = renderer.createApp(ModelScreen)
    app.mount(root)
    const settle = async () => {
        for (let step = 0; step < 6; step++) {
            await Promise.resolve()
            await nextTick()
        }
    }
    const all = (node = root) => [node, ...node.children.flatMap((child) => all(child))]
    const text = (node = root) => (node.type === 'comment' ? '' : `${node.text} ${node.children.map((child) => text(child)).join(' ')}`)
    const button = (label) => {
        const element = all().find((node) => node.type === 'button' && text(node).trim() === label)
        assert.ok(element, `버튼을 찾을 수 없습니다: ${label}`)
        return element
    }
    await settle()
    return { all, text, button, settle, cleanup: () => app.unmount() }
}

const serverError = (message) => ({ response: { status: 503, data: { message } } })
const version = {
    id: 'version-from-server',
    modelId: 'model-from-server',
    modelName: '서버에서 받은 모델 이름',
    version: 'server-version-7',
    baseModelRef: 'server/model-reference',
    artifactType: 'LORA',
    status: 'READY',
    createdAt: '2026-10-21T00:00:00Z',
    latestEvaluationScore: null,
    evaluations: [],
    deployments: [],
    readiness: { canDeploy: false, canRollback: false, checks: [] },
}
const registry = {
    models: [],
    versions: [version],
    trainingJobs: [],
    deployments: [],
    capabilities: { trainingExecution: false, deploymentExecution: false, rollbackExecution: false },
    truncated: { models: false, versions: false, trainingJobs: false, deployments: false },
    limit: 100,
}
const runtime = { status: 'AVAILABLE', model: 'runtime-model-from-server', message: '서버가 반환한 상태 안내', checkedAt: '2026-10-21T00:00:00Z' }

test('목록 조회에 실패해도 추론 서버 응답을 표시하고 오류를 예시 값으로 숨기지 않는다', async () => {
    const screen = await mountScreen({
        getModelRegistry: async () => {
            throw serverError('모델 관리 목록을 조회할 수 없습니다.')
        },
        getModelRuntime: async () => runtime,
    })
    try {
        const visible = screen.text()
        assert.match(visible, /모델 관리 목록을 조회할 수 없습니다\./)
        assert.match(visible, /runtime-model-from-server/)
        assert.match(visible, /서버가 반환한 상태 안내/)
        assert.ok(screen.all().some((node) => node.props.role === 'alert' && screen.text(node).includes('목록을 조회할 수 없습니다')))
        assert.equal(screen.button('모델 버전 등록').props.disabled, true)
        assert.doesNotMatch(visible, /doenaai-code-analyzer|검증 정확도|서버에서 받은 모델 이름/)
    } finally {
        screen.cleanup()
    }
})

test('추론 서버 확인에 실패해도 저장된 목록을 표시하고 측정하지 않은 점수를 만들지 않는다', async () => {
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => {
            throw serverError('추론 서버 응답을 확인할 수 없습니다.')
        },
    })
    try {
        const visible = screen.text()
        assert.match(visible, /추론 서버 응답을 확인할 수 없습니다\./)
        assert.match(visible, /서버에서 받은 모델 이름/)
        assert.match(visible, /server-version-7/)
        assert.match(visible, /미측정/)
        assert.equal(screen.button('모델 버전 등록').props.disabled, false)
        assert.doesNotMatch(visible, /runtime-model-from-server|검증 정확도|doenaai-code-analyzer/)
    } finally {
        screen.cleanup()
    }
})

test('등록 저장 뒤 목록 갱신이 실패해도 저장 성공 안내와 상세를 보존한다', async () => {
    let registryCalls = 0
    const savedBodies = []
    const screen = await mountScreen({
        getModelRegistry: async () => {
            if (++registryCalls === 1) return registry
            throw serverError('저장 후 목록을 새로 불러올 수 없습니다.')
        },
        getModelRuntime: async () => runtime,
        getModelVersion: async () => version,
        registerModelVersion: async (body) => {
            savedBodies.push(body)
            return version
        },
    })
    try {
        screen.button('모델 버전 등록').props.onClick()
        await screen.settle()
        for (const [label, value] of [
            ['새 모델 이름', '새로 등록할 모델'],
            ['기반 모델 식별자', 'server/new-model'],
            ['버전', 'new-version-1'],
        ]) {
            const input = screen.all().find((node) => node.props['data-label'] === label)
            assert.ok(input, `입력 요소를 찾을 수 없습니다: ${label}`)
            input.props.onInput({ target: { value } })
        }
        await screen.settle()
        const form = screen.all().find((node) => node.type === 'form')
        assert.ok(form)
        form.props.onSubmit({ preventDefault() {} })
        await screen.settle()
        const visible = screen.text()
        assert.equal(savedBodies.length, 1)
        assert.equal(savedBodies[0].modelName, '새로 등록할 모델')
        assert.match(visible, /모델 버전 등록 정보를 서버에 저장했습니다\./)
        assert.match(visible, /저장 후 목록을 새로 불러올 수 없습니다\./)
        assert.match(visible, /최신 상태가 아니므로 등록은 잠시 사용할 수 없습니다/)
        assert.ok(screen.all().some((node) => node.props.role === 'dialog' && node.props['aria-label'] === '모델 버전 상세'))
        assert.doesNotMatch(visible, /등록 정보를 저장하지 못했습니다/)
    } finally {
        screen.cleanup()
    }
})

const installed = {
    model: 'current:model',
    currentRevision: 7,
    previousModel: 'previous:model',
    canRollback: true,
    models: [{ name: 'candidate:model', digest: 'a'.repeat(64), selectable: true, capabilities: ['completion'] }],
}
const selectCandidate = async (screen) => {
    screen.button('사용 모델 변경').props.onClick()
    await screen.settle()
    const input = screen.all().find((node) => node.props['data-label'] === '전환할 설치 모델')
    input.props.onChange({ target: { value: 'candidate:model' } })
    await screen.settle()
}
const probeResult = {
    status: 'PASSED',
    model: 'candidate:model',
    digest: 'a'.repeat(64),
    currentRevision: 7,
    probeToken: 'test-proof-token-only',
    expiresAt: '2099-01-01T00:00:00Z',
}

test('조회 권한만 있는 사용자는 설치 목록을 보되 검증·전환·복구를 실행하지 못한다', async () => {
    let writes = 0
    const screen = await mountScreen(
        {
            getModelRegistry: async () => registry,
            getModelRuntime: async () => runtime,
            getInstalledModels: async () => installed,
            probeInstalledModel: async () => {
                writes += 1
            },
        },
        ['MODEL_READ']
    )
    try {
        await selectCandidate(screen)
        assert.equal(screen.button('JSON 응답 검증').props.disabled, true)
        assert.equal(screen.button('모델 전환').props.disabled, true)
        assert.equal(screen.button('이전 모델로 복구').props.disabled, true)
        screen.button('JSON 응답 검증').props.onClick()
        await screen.settle()
        assert.equal(writes, 0)
    } finally {
        screen.cleanup()
    }
})

test('JSON 검증 중 중복 클릭을 막고 선택이 바뀌면 전환용 증거를 버린다', async () => {
    let calls = 0
    let complete
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getInstalledModels: async () => installed,
        probeInstalledModel: () => {
            calls += 1
            return new Promise((resolve) => {
                complete = resolve
            })
        },
    })
    try {
        await selectCandidate(screen)
        const verify = screen.button('JSON 응답 검증')
        verify.props.onClick()
        verify.props.onClick()
        assert.equal(calls, 1)
        complete(probeResult)
        await screen.settle()
        assert.equal(screen.button('모델 전환').props.disabled, false)
        const input = screen.all().find((node) => node.props['data-label'] === '전환할 설치 모델')
        input.props.onChange({ target: { value: '' } })
        await screen.settle()
        assert.equal(screen.button('모델 전환').props.disabled, true)
    } finally {
        screen.cleanup()
    }
})

test('기존 검증 조회에 전환 토큰이 없으면 새 시험을 안내하고 적용을 차단한다', async () => {
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getInstalledModels: async () => installed,
        probeInstalledModel: async () => ({ ...probeResult, probeToken: null }),
    })
    try {
        await selectCandidate(screen)
        screen.button('JSON 응답 검증').props.onClick()
        await screen.settle()
        assert.equal(screen.button('모델 전환').props.disabled, true)
        assert.match(screen.text(), /전환용 시험 토큰은 다시 발급되지 않습니다/)
    } finally {
        screen.cleanup()
    }
})

test('전환 결과가 불명확하면 자동 재실행하지 않고 같은 식별자로만 수동 재시도한다', async () => {
    const calls = []
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getInstalledModels: async () => installed,
        probeInstalledModel: async () => probeResult,
        activateInstalledModel: async (body) => {
            calls.push(body)
            return { status: 'UNKNOWN' }
        },
    })
    try {
        await selectCandidate(screen)
        screen.button('JSON 응답 검증').props.onClick()
        await screen.settle()
        screen.button('모델 전환').props.onClick()
        await screen.settle()
        const checkbox = screen.all().find((node) => node.type === 'input' && node.props.type === 'checkbox')
        assert.ok(checkbox)
        checkbox.props['onUpdate:modelValue'](true)
        await screen.settle()
        screen.button('확인 후 전환').props.onClick()
        await screen.settle()
        assert.equal(calls.length, 1)
        assert.match(screen.text(), /작업 결과가 아직 확정되지 않았습니다/)
        screen.button('같은 요청 수동 재시도').props.onClick()
        await screen.settle()
        assert.equal(calls.length, 2)
        assert.equal(calls[0].operationId, calls[1].operationId)
        assert.deepEqual(calls[0], calls[1])
    } finally {
        screen.cleanup()
    }
})

test('학습은 확인 전 실행하지 않고 횟수를 검사하며 불명확한 응답 뒤에도 요청 식별자를 보존한다', async () => {
    const calls = []
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({
            dataset: {
                target: { sourceModel: 'qwen3.8:27b', baseModel: 'mlx-community/Qwen3.8-27B-4bit', revision: 'a'.repeat(40), digest: 'b'.repeat(64) },
                samples: [],
                total: 0,
            },
            capabilities: { trainingExecution: true },
            jobs: [],
        }),
        startModelTraining: async (body) => {
            calls.push(body)
            return { status: 'UNKNOWN' }
        },
    })
    try {
        screen.button('학습 작업').props.onClick()
        await screen.settle()
        screen.button('학습 시작').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /GPU·메모리·전원과 저장 공간/)
        assert.equal(screen.button('확인 후 학습 시작').props.disabled, true)
        assert.equal(calls.length, 0)
        const input = screen.all().find((node) => node.props['data-label'] === '학습 반복 횟수')
        const checkbox = screen.all().find((node) => node.type === 'input' && node.props.type === 'checkbox')
        checkbox.props['onUpdate:modelValue'](true)
        input.props.onInput({ target: { value: '0' } })
        await screen.settle()
        screen.button('확인 후 학습 시작').props.onClick()
        await screen.settle()
        assert.equal(calls.length, 0)
        assert.match(screen.text(), /1부터 20 사이의 정수/)
        input.props.onInput({ target: { value: '2' } })
        await screen.settle()
        const start = screen.button('확인 후 학습 시작')
        start.props.onClick()
        start.props.onClick()
        await screen.settle()
        assert.equal(calls.length, 1)
        assert.equal(calls[0].iterations, 2)
        assert.equal(calls[0].model, 'qwen3.8:27b')
        assert.equal(calls[0].digest, 'b'.repeat(64))
        screen.button('같은 요청 수동 재시도').props.onClick()
        await screen.settle()
        assert.equal(calls.length, 2)
        assert.equal(calls[0].operationId, calls[1].operationId)
    } finally {
        screen.cleanup()
    }
})

test('학습 접수 뒤 목록 조회 실패를 시작 실패로 바꾸거나 새 학습을 자동 요청하지 않는다', async () => {
    let listing = 0
    let starts = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => {
            if (++listing > 1) throw serverError('학습 목록 재조회 실패')
            return {
                dataset: {
                    target: { sourceModel: 'qwen3.8:27b', baseModel: 'mlx-community/Qwen3.8-27B-4bit', revision: 'a'.repeat(40), digest: 'b'.repeat(64) },
                    samples: [],
                    total: 0,
                },
                capabilities: { trainingExecution: true },
                jobs: [],
            }
        },
        startModelTraining: async () => {
            starts += 1
            return { status: 'PENDING', message: '학습 요청을 접수했습니다.' }
        },
    })
    try {
        screen.button('학습 작업').props.onClick()
        await screen.settle()
        screen.button('학습 시작').props.onClick()
        await screen.settle()
        screen
            .all()
            .find((node) => node.type === 'input' && node.props.type === 'checkbox')
            .props['onUpdate:modelValue'](true)
        await screen.settle()
        screen.button('확인 후 학습 시작').props.onClick()
        await screen.settle()
        assert.equal(starts, 1)
        assert.match(screen.text(), /학습 요청을 접수했습니다/)
        assert.match(screen.text(), /학습 목록 재조회 실패/)
        assert.equal(screen.button('학습 시작').props.disabled, true)
    } finally {
        screen.cleanup()
    }
})

test('학습 생성 모델과 기반 버전이 없는 이전 서버 응답에서는 학습 시작을 막는다', async () => {
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ capabilities: { trainingExecution: true }, jobs: [] }),
    })
    try {
        screen.button('학습 작업').props.onClick()
        await screen.settle()
        assert.equal(screen.button('학습 시작').props.disabled, true)
        assert.match(screen.text(), /학습 대상 모델을 확인하지 못했습니다/)
    } finally {
        screen.cleanup()
    }
})
