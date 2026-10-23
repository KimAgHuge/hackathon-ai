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
        'synchronizeModelHistory',
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
        'prepareModelAdapter',
        'getTrainedModelBuild',
        'startTrainedModelBuild',
        'cancelTrainedModelBuild',
        'importTrainedModel',
        'getTrainedModelImport',
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
const { default: TrainedModelPanel } = await import(await compileComponent(new URL('../src/components/models/ModelTrainedModelPanel.vue', import.meta.url)))

const operationPage = (operations, pagination = {}) => ({
    operations,
    page: 0,
    pageSize: 10,
    totalCount: operations.length,
    totalPages: Math.ceil(operations.length / 10),
    ...pagination,
})

const makeNode = (type, text = '') => ({ type, text, children: [], props: {}, parent: null, style: {}, addEventListener() {}, removeEventListener() {} })
const mountScreen = async (api, permissions = null, options = {}) => {
    const visibilityListeners = new Set()
    globalThis.document = {
        hidden: false,
        addEventListener(name, listener) {
            if (name === 'visibilitychange') visibilityListeners.add(listener)
        },
        removeEventListener(name, listener) {
            if (name === 'visibilitychange') visibilityListeners.delete(listener)
        },
    }
    globalThis[dependenciesKey].api = {
        getInstalledModels: async () => ({
            model: 'qwen3.8:27b',
            currentRevision: 0,
            models: [{ name: 'qwen3.8:27b', digest: 'b'.repeat(64), selectable: true }],
            canRollback: false,
        }),
        getModelTraining: async () => ({ capabilities: { trainingExecution: false }, jobs: [] }),
        getModelOperations: async () => operationPage([]),
        getTrainedModelBuild: async (id) => ({ jobId: id, sourceModel: 'qwen3.8:27b', status: 'NOT_BUILT', available: true, progress: 0 }),
        getTrainedModelImport: async (id) => ({ jobId: id, sourceModel: 'qwen3.8:27b', model: `doenaai-trained:web-${id}`, status: 'NOT_STARTED' }),
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
    const app = renderer.createApp(options.component || ModelScreen, options.props)
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
        const element = all().find((node) => node.type === 'button' && text(node).trim().replace(/\s+/g, ' ') === label)
        assert.ok(element, `버튼을 찾을 수 없습니다: ${label}`)
        return element
    }
    await settle()
    return {
        all,
        text,
        button,
        settle,
        async setHidden(hidden) {
            document.hidden = hidden
            for (const listener of visibilityListeners) listener()
            await settle()
        },
        cleanup: () => app.unmount(),
    }
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
    capabilities: { trainingExecution: false, deploymentExecution: false, rollbackExecution: false, historySynchronization: true },
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
        assert.ok(!screen.all().some((node) => node.type === 'button' && screen.text(node).trim() === '외부 모델 정보 등록'))
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
        const registration = screen.button('외부 모델 정보 등록')
        assert.equal(registration.props.disabled, false)
        let parent = registration.parent
        while (parent && parent.type !== 'details') parent = parent.parent
        assert.ok(parent)
        assert.equal(parent.props['aria-label'], '모델 버전 기타 관리')
        assert.ok(!parent.props.open)
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
        screen.button('외부 모델 정보 등록').props.onClick()
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
        const registrationDialog = screen.all().find((node) => node.props.role === 'dialog' && node.props['aria-label'] === '외부 모델 정보 등록')
        assert.ok(registrationDialog)
        const form = screen.all(registrationDialog).find((node) => node.type === 'form')
        assert.ok(form)
        form.props.onSubmit({ preventDefault() {} })
        await screen.settle()
        const visible = screen.text()
        assert.equal(savedBodies.length, 1)
        assert.equal(savedBodies[0].modelName, '새로 등록할 모델')
        assert.match(visible, /외부 모델의 관리 정보를 저장했습니다\./)
        assert.match(visible, /저장 후 목록을 새로 불러올 수 없습니다\./)
        assert.match(visible, /최신 상태가 아니므로 등록은 잠시 사용할 수 없습니다/)
        assert.ok(screen.all().some((node) => node.props.role === 'dialog' && node.props['aria-label'] === '모델 버전 상세'))
        assert.doesNotMatch(visible, /등록 정보를 저장하지 못했습니다/)
    } finally {
        screen.cleanup()
    }
})

test('자동 등록한 버전 상세는 실행 엔진 등록 정보와 확인된 준비 상태를 표시한다', async () => {
    const automaticVersion = {
        ...version,
        artifactType: 'MERGED',
        historySource: 'TRAINED_MODEL_IMPORT',
        registrationVerified: true,
        runtimeModel: 'trained:model',
        runtimeDigest: 'd'.repeat(64),
        trainingJobId: 'completed-training-job',
        readiness: {
            reason: '실행 엔진 등록을 확인했습니다.',
            checks: [{ key: 'runtime', label: '실행 엔진 등록', status: 'PASSED', message: '등록 확인 완료' }],
        },
    }
    const screen = await mountScreen({
        getModelRegistry: async () => ({ ...registry, versions: [automaticVersion] }),
        getModelRuntime: async () => runtime,
        getModelVersion: async () => automaticVersion,
    })
    try {
        const detailButton = screen.all().find((node) => node.type === 'button' && screen.text(node).trim() === '상세 보기')
        detailButton.props.onClick()
        await screen.settle()
        assert.match(screen.text(), /모델 작업 자동 기록/)
        assert.match(screen.text(), /trained:model/)
        assert.match(screen.text(), /completed-training-job/)
        assert.match(screen.text(), /실행 엔진 등록을 확인한 모델입니다/)
        screen.button('배포 준비').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /실행 엔진에 등록된 모델입니다/)
        assert.match(screen.text(), /등록 확인 완료/)
        assert.doesNotMatch(screen.text(), /등록 파일 직접 배포 불가|직접 적용하는 기능은 지원하지 않습니다|직접 저장한 관리 정보입니다/)
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
    screen.button('모델 작업').props.onClick()
    await screen.settle()
    const input = screen.all().find((node) => node.props['data-label'] === '작업할 설치 모델')
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
        assert.ok(!screen.all().some((node) => node.type === 'button' && screen.text(node).trim() === '기록 동기화'))
        await selectCandidate(screen)
        assert.equal(screen.button('JSON 응답 검증').props.disabled, true)
        assert.equal(screen.button('분석 모델로 사용').props.disabled, true)
        assert.equal(screen.button('이전 모델로 복구').props.disabled, true)
        screen.button('JSON 응답 검증').props.onClick()
        await screen.settle()
        assert.equal(writes, 0)
    } finally {
        screen.cleanup()
    }
})

test('동기화 API가 없는 기존 서버에서는 실행할 수 없는 기록 동기화 버튼을 표시하지 않는다', async () => {
    const screen = await mountScreen({
        getModelRegistry: async () => ({ ...registry, capabilities: { ...registry.capabilities, historySynchronization: false } }),
        getModelRuntime: async () => runtime,
    })
    try {
        assert.ok(!screen.all().some((node) => node.type === 'button' && screen.text(node).trim() === '기록 동기화'))
    } finally {
        screen.cleanup()
    }
})

test('기록 동기화는 즉시 진행을 알리고 중복 요청을 차단한 뒤 버전·배포·실행 기록을 갱신한다', async () => {
    let finishSync,
        writes = 0,
        reads = 0,
        operationsReads = 0,
        synchronized = false
    const screen = await mountScreen({
        getModelRegistry: async () => {
            reads += 1
            return synchronized
                ? { ...registry, versions: [{ ...version, version: '동기화한 버전' }], deployments: [{ id: 'deployment', version: '확인된 배포' }] }
                : registry
        },
        getModelRuntime: async () => runtime,
        getModelOperations: async () => {
            operationsReads += 1
            return operationPage([])
        },
        synchronizeModelHistory: () => {
            writes += 1
            return new Promise((resolve) => {
                finishSync = resolve
            })
        },
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        const previousOperationsReads = operationsReads
        const button = screen.button('기록 동기화')
        button.props.onClick()
        button.props.onClick()
        await screen.settle()
        assert.equal(writes, 1)
        assert.equal(screen.button('기록 동기화').props.disabled, true)
        assert.match(screen.text(), /학습이나 모델 전환을 다시 실행하지 않습니다/)
        synchronized = true
        finishSync({ status: 'SUCCEEDED', registrationsChecked: 1, deploymentsChecked: 1, failures: 0, message: '기록 동기화를 완료했습니다.' })
        await screen.settle()
        assert.equal(screen.button('기록 동기화').props.disabled, false)
        assert.equal(reads, 2)
        assert.equal(operationsReads, previousOperationsReads + 1)
        assert.match(screen.text(), /기록 동기화를 완료했습니다/)
        await selectManagementTab(screen, '모델 버전')
        assert.match(screen.text(), /동기화한 버전/)
        await selectManagementTab(screen, '배포 이력')
        assert.match(screen.text(), /확인된 배포/)
    } finally {
        screen.cleanup()
    }
})

test('기록 일부 동기화 실패와 응답 끊김은 모델 작업 실패로 바꾸지 않고 수동 재시도를 제공한다', async () => {
    let calls = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        synchronizeModelHistory: async () => {
            calls += 1
            if (calls === 1) return { status: 'PARTIAL', failures: 1, message: '일부 기록을 보완하지 못했습니다. 기록 동기화를 다시 실행해 주세요.' }
            throw serverError('기록 연결을 확인하지 못했습니다.')
        },
    })
    try {
        screen.button('기록 동기화').props.onClick()
        await screen.settle()
        assert.ok(screen.all().some((node) => node.props.role === 'alert' && screen.text(node).includes('일부 기록')))
        assert.match(screen.text(), /server-version-7/)
        assert.equal(calls, 1)
        screen.button('기록 동기화').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /기록 연결을 확인하지 못했습니다/)
        assert.equal(calls, 2)
        assert.equal(screen.button('기록 동기화').props.disabled, false)
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
        await screen.settle()
        assert.equal(screen.all().find((node) => node.props['data-label'] === '작업할 설치 모델').props.disabled, true)
        assert.equal(screen.button('선택 모델 학습 승인 자료로 시험 학습').props.disabled, true)
        complete(probeResult)
        await screen.settle()
        assert.equal(screen.all().find((node) => node.props['data-label'] === '작업할 설치 모델').props.disabled, false)
        assert.equal(screen.button('선택 모델 학습 승인 자료로 시험 학습').props.disabled, false)
        assert.equal(screen.button('분석 모델로 사용').props.disabled, false)
        const input = screen.all().find((node) => node.props['data-label'] === '작업할 설치 모델')
        input.props.onChange({ target: { value: '' } })
        await screen.settle()
        assert.equal(screen.button('분석 모델로 사용').props.disabled, true)
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
        assert.equal(screen.button('분석 모델로 사용').props.disabled, true)
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
        screen.button('분석 모델로 사용').props.onClick()
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

test('실제 적용 성공 뒤 기록 저장이 실패해도 적용 완료를 보존하고 기록 동기화만 안내한다', async () => {
    let calls = 0,
        registryReads = 0
    const screen = await mountScreen({
        getModelRegistry: async () => {
            registryReads += 1
            return registry
        },
        getModelRuntime: async () => runtime,
        getInstalledModels: async () => installed,
        probeInstalledModel: async () => probeResult,
        activateInstalledModel: async () => {
            calls += 1
            return { status: 'APPLIED', message: '새 모델 적용을 완료했습니다.', historySyncRequired: true }
        },
    })
    try {
        await selectCandidate(screen)
        screen.button('JSON 응답 검증').props.onClick()
        await screen.settle()
        screen.button('분석 모델로 사용').props.onClick()
        await screen.settle()
        await checkConfirmation(screen)
        screen.button('확인 후 전환').props.onClick()
        await screen.settle()
        assert.equal(calls, 1)
        assert.equal(registryReads, 2)
        assert.match(screen.text(), /새 모델 적용을 완료했습니다/)
        assert.match(screen.text(), /모델 작업 결과는 유지되었지만 관리 기록을 저장하지 못했습니다/)
        assert.match(screen.text(), /모델 전환을 다시 요청할 필요는 없습니다/)
        assert.doesNotMatch(screen.text(), /작업 결과가 아직 확정되지 않았습니다/)
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
        screen.button('모델 작업').props.onClick()
        await screen.settle()
        screen.button('선택 모델 학습 승인 자료로 시험 학습').props.onClick()
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
        screen.button('모델 작업').props.onClick()
        await screen.settle()
        screen.button('선택 모델 학습 승인 자료로 시험 학습').props.onClick()
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
        screen.button('모델 작업').props.onClick()
        await screen.settle()
        screen.button('선택 모델 학습 승인 자료로 시험 학습').props.onClick()
        await screen.settle()
        assert.equal(screen.button('학습 시작').props.disabled, true)
        assert.match(screen.text(), /학습 대상 모델을 확인하지 못했습니다/)
    } finally {
        screen.cleanup()
    }
})

test('학습 시작 확인 중 기반 버전이 바뀌면 이전 확인을 해제한다', async () => {
    let version = 'a'
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({
            requestedModel: 'qwen3.8:27b',
            dataset: {
                target: { sourceModel: 'qwen3.8:27b', baseModel: 'mlx-community/Qwen3.8-27B-4bit', revision: version.repeat(40), digest: version.repeat(64) },
                samples: [],
                total: 0,
            },
            capabilities: { trainingExecution: true },
            jobs: [],
        }),
    })
    try {
        screen.button('모델 작업').props.onClick()
        await screen.settle()
        screen.button('선택 모델 학습 승인 자료로 시험 학습').props.onClick()
        await screen.settle()
        screen.button('학습 시작').props.onClick()
        await screen.settle()
        screen
            .all()
            .find((node) => node.type === 'input' && node.props.type === 'checkbox')
            .props['onUpdate:modelValue'](true)
        await screen.settle()
        assert.equal(screen.button('확인 후 학습 시작').props.disabled, false)
        version = 'b'
        screen.button('작업 새로고침').props.onClick()
        await screen.settle()
        assert.equal(screen.button('확인 후 학습 시작').props.disabled, true)
        assert.match(screen.text(), /기반 버전이 바뀌었습니다/)
    } finally {
        screen.cleanup()
    }
})

const completedTraining = {
    id: '424b376d-2419-4c80-a776-87d7d863b6d2',
    status: 'SUCCEEDED',
    iterations: 10,
    target: { sourceModel: 'qwen3.8:27b', baseModel: 'mlx-community/Qwen3.8-27B-4bit', revision: 'a'.repeat(40), digest: 'b'.repeat(64) },
}
const preparedModel = {
    status: 'READY',
    jobId: completedTraining.id,
    model: `doenaai-trained:web-${completedTraining.id}`,
    digest: 'c'.repeat(64),
    sourceModel: 'qwen3.8:27b',
    trainingJobId: completedTraining.id,
    trainedModel: true,
    message: '학습 어댑터 준비를 확인했습니다.',
}
const openAdapterAction = async (screen) => {
    screen.button('모델 작업').props.onClick()
    await screen.settle()
    screen.button('학습 결과 적용 새 모델 생성과 적용').props.onClick()
    await screen.settle()
    screen
        .all()
        .find((node) => node.props['data-label'] === '완료한 학습 결과')
        .props.onChange({ target: { value: completedTraining.id } })
    await screen.settle()
}
const checkConfirmation = async (screen) => {
    screen
        .all()
        .find((node) => node.type === 'input' && node.props.type === 'checkbox')
        .props['onUpdate:modelValue'](true)
    await screen.settle()
}

test('선택한 다른 모델을 운영 모델로 바꾸지 않고 그 모델의 자료로 학습한다', async () => {
    const queries = [],
        starts = []
    let transitions = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getInstalledModels: async () => ({
            model: 'current:model',
            currentRevision: 1,
            models: [
                { name: 'current:model', selectable: true },
                { name: 'qwen3.8:27b', digest: 'b'.repeat(64), selectable: true },
            ],
        }),
        getModelTraining: async (model) => {
            queries.push(model)
            return { dataset: { target: completedTraining.target, total: 3, samples: [] }, capabilities: { trainingExecution: true }, jobs: [] }
        },
        startModelTraining: async (body) => {
            starts.push(body)
            return { status: 'PENDING' }
        },
        activateInstalledModel: async () => {
            transitions += 1
        },
    })
    try {
        screen.button('모델 작업').props.onClick()
        await screen.settle()
        screen
            .all()
            .find((node) => node.props['data-label'] === '작업할 설치 모델')
            .props.onChange({ target: { value: 'qwen3.8:27b' } })
        screen.button('선택 모델 학습 승인 자료로 시험 학습').props.onClick()
        await screen.settle()
        assert.ok(queries.every((model) => model === 'qwen3.8:27b'))
        screen.button('학습 시작').props.onClick()
        await screen.settle()
        await checkConfirmation(screen)
        screen.button('확인 후 학습 시작').props.onClick()
        await screen.settle()
        assert.equal(starts[0].model, 'qwen3.8:27b')
        assert.equal(transitions, 0)
    } finally {
        screen.cleanup()
    }
})

test('다른 모델이 학습 중이면 선택 모델의 작업 목록이 비어도 새 학습을 막는다', async () => {
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({
            busy: true,
            jobs: [],
            dataset: { target: completedTraining.target, total: 3 },
            capabilities: { trainingExecution: true },
        }),
    })
    try {
        screen.button('모델 작업').props.onClick()
        await screen.settle()
        screen.button('선택 모델 학습 승인 자료로 시험 학습').props.onClick()
        await screen.settle()
        assert.equal(screen.button('학습 시작').props.disabled, true)
        assert.match(screen.text(), /학습 서버에서 진행 중인 작업/)
    } finally {
        screen.cleanup()
    }
})

test('조회 권한만 있으면 완료 학습을 보되 새 모델을 생성하지 못한다', async () => {
    let preparations = 0
    const screen = await mountScreen(
        {
            getModelRegistry: async () => registry,
            getModelRuntime: async () => runtime,
            getModelTraining: async () => ({ jobs: [completedTraining] }),
            startTrainedModelBuild: async () => {
                preparations += 1
            },
        },
        ['MODEL_READ']
    )
    try {
        await openAdapterAction(screen)
        assert.equal(screen.button('새 학습 모델 생성').props.disabled, true)
        screen.button('새 학습 모델 생성').props.onClick()
        await screen.settle()
        assert.equal(preparations, 0)
    } finally {
        screen.cleanup()
    }
})

test('학습 대상 상세가 없는 이전 작업도 생성 모델로 구분하고 안전하게 표시한다', async () => {
    const legacy = { ...completedTraining, target: undefined, sourceModel: 'qwen3.8:27b' }
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ requestedModel: 'qwen3.8:27b', jobs: [legacy] }),
        getModelOperations: async () => operationPage([{ ...legacy, action: 'TRAINING_START' }]),
    })
    try {
        screen.button('실행 작업 이력').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /qwen3\.8:27b/)
        assert.doesNotMatch(screen.text(), /기존 작업: 모델 미기록/)
        await openAdapterAction(screen)
        assert.match(screen.text(), /학습 기반 모델 · 미기록/)
        assert.equal(screen.button('새 학습 모델 생성').props.disabled, false)
    } finally {
        screen.cleanup()
    }
})

test('설치된 새 학습 모델을 선택하면 그 모델의 연속 학습 결과와 자료 출처를 구분한다', async () => {
    const requested = []
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getInstalledModels: async () => ({
            model: preparedModel.model,
            currentRevision: 1,
            models: [
                {
                    name: preparedModel.model,
                    sourceModel: 'qwen3.8:27b',
                    trainingJobId: completedTraining.id,
                    trainedModel: true,
                    selectable: true,
                    digest: preparedModel.digest,
                },
            ],
        }),
        getModelTraining: async (model) => {
            requested.push(model)
            return {
                requestedModel: preparedModel.model,
                jobs: [{ ...completedTraining, target: { ...completedTraining.target, selectedModel: preparedModel.model } }],
            }
        },
    })
    try {
        await openAdapterAction(screen)
        assert.ok(requested.every((model) => model === preparedModel.model))
        assert.match(screen.text(), /선택한 학습 모델 · doenaai-trained:web-/)
        assert.match(screen.text(), /승인 자료 출처 모델 · qwen3\.8:27b/)
        assert.equal(screen.button('새 학습 모델 생성').props.disabled, false)
    } finally {
        screen.cleanup()
    }
})

test('파생 모델의 선택 식별자와 이전 어댑터를 유지해 연속 학습을 요청한다', async () => {
    const queries = [],
        starts = []
    const target = { ...completedTraining.target, selectedModel: preparedModel.model, resumeJobId: completedTraining.id }
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getInstalledModels: async () => ({
            model: preparedModel.model,
            currentRevision: 1,
            models: [{ name: preparedModel.model, sourceModel: 'qwen3.8:27b', trainedModel: true, trainingJobId: completedTraining.id, selectable: true }],
        }),
        getModelTraining: async (model) => {
            queries.push(model)
            return {
                requestedModel: preparedModel.model,
                selectedModel: preparedModel.model,
                dataset: { target, total: 3, samples: [{ caseId: 'approved-case', sourceModel: 'qwen3.8:27b' }] },
                capabilities: { trainingExecution: true },
                jobs: [],
                trainingProfile: {
                    trainer: 'qwen-mlp-trial-v1',
                    modelType: 'qwen3_5',
                    loraLayers: 1,
                    loraRank: 4,
                    maxSequenceLength: 18000,
                    batchSize: 1,
                    continuation: true,
                    parentJobId: completedTraining.id,
                },
                modelExport: { supported: true, reasonCode: 'READY', architecture: 'qwen35', message: '변환 경로를 확인했습니다.' },
            }
        },
        startModelTraining: async (body) => {
            starts.push(body)
            return { status: 'PENDING' }
        },
    })
    try {
        screen.button('모델 작업').props.onClick()
        await screen.settle()
        screen.button('선택 모델 학습 승인 자료로 시험 학습').props.onClick()
        await screen.settle()
        assert.ok(queries.every((model) => model === preparedModel.model))
        assert.match(screen.text(), /선택한 이전 어댑터에서 연속 학습/)
        assert.match(screen.text(), /승인 자료 출처 모델 · qwen3\.8:27b/)
        assert.match(screen.text(), /qwen-mlp-trial-v1/)
        assert.equal(screen.button('학습 시작').props.disabled, false)
        screen.button('학습 시작').props.onClick()
        await screen.settle()
        await checkConfirmation(screen)
        screen.button('확인 후 학습 시작').props.onClick()
        await screen.settle()
        assert.equal(starts[0].model, preparedModel.model)
        assert.equal(starts[0].digest, target.digest)
    } finally {
        screen.cleanup()
    }
})

test('일반 MLX 학습 설정과 변환 미지원을 따로 표시하고 학습은 허용한다', async () => {
    const model = 'example:7b',
        starts = []
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getInstalledModels: async () => ({ model, currentRevision: 1, models: [{ name: model, selectable: true }] }),
        getModelTraining: async () => ({
            requestedModel: model,
            dataset: { target: { ...completedTraining.target, sourceModel: model, baseModel: 'example/local-7b' }, total: 3 },
            capabilities: { trainingExecution: true },
            jobs: [],
            trainingProfile: {
                trainer: 'mlx-lora-v1',
                modelType: 'gemma3',
                loraLayers: 4,
                loraRank: 8,
                maxSequenceLength: 2048,
                batchSize: 1,
                continuation: false,
                directory: '/private/model',
            },
            modelExport: { supported: false, reasonCode: 'MODEL_BUILD_UNSUPPORTED_PROFILE', message: '이 모델의 변환 경로를 준비해야 합니다.' },
        }),
        startModelTraining: async (body) => {
            starts.push(body)
            return { status: 'PENDING' }
        },
    })
    try {
        screen.button('모델 작업').props.onClick()
        await screen.settle()
        screen.button('선택 모델 학습 승인 자료로 시험 학습').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /mlx-lora-v1/)
        assert.match(screen.text(), /2048/)
        assert.match(screen.text(), /분석용 모델 변환 · 지원 준비 필요/)
        assert.doesNotMatch(screen.text(), /\/private\/model/)
        assert.equal(screen.button('학습 시작').props.disabled, false)
        screen.button('학습 시작').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /어댑터 학습·저장·재로딩까지 확인합니다/)
        await checkConfirmation(screen)
        screen.button('확인 후 학습 시작').props.onClick()
        await screen.settle()
        assert.equal(starts[0].model, model)
    } finally {
        screen.cleanup()
    }
})

test('파생 모델 조회가 원본 모델로 바뀐 응답이면 자동 대체 학습을 막는다', async () => {
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getInstalledModels: async () => ({
            model: preparedModel.model,
            currentRevision: 1,
            models: [{ name: preparedModel.model, sourceModel: 'qwen3.8:27b', selectable: true }],
        }),
        getModelTraining: async () => ({
            requestedModel: 'qwen3.8:27b',
            dataset: { target: completedTraining.target, total: 3 },
            capabilities: { trainingExecution: true },
            jobs: [],
        }),
    })
    try {
        screen.button('모델 작업').props.onClick()
        await screen.settle()
        screen.button('선택 모델 학습 승인 자료로 시험 학습').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /선택한 모델과 학습 서버가 확인한 대상이 다릅니다/)
        assert.equal(screen.button('학습 시작').props.disabled, true)
    } finally {
        screen.cleanup()
    }
})

test('선택 모델과 다른 학습 대상 응답은 적용할 자료로 표시하지 않는다', async () => {
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ requestedModel: 'other:model', jobs: [completedTraining] }),
    })
    try {
        await openAdapterAction(screen)
        assert.match(screen.text(), /선택한 모델과 서버가 확인한 학습 대상이 다릅니다/)
        assert.equal(screen.button('새 학습 모델 생성').props.disabled, true)
    } finally {
        screen.cleanup()
    }
})

const trainingOperation = {
    id: '78a368cd-1ed1-4fc6-b67f-46eb53dcae44',
    action: 'TRAINING_START',
    status: 'TRAINING',
    progress: 45,
    iterations: 10,
    target: completedTraining.target,
    createdAt: '2026-10-21T09:27:35Z',
    updatedAt: '2026-10-21T09:28:35Z',
    message: '서버에서 시험 학습을 진행하고 있습니다.',
}
const managementTabs = (screen) => {
    const navigation = screen.all().find((node) => node.props['aria-label'] === '모델 관리 목록')
    assert.ok(navigation)
    return navigation.children.filter((node) => node.type === 'button')
}
const selectManagementTab = async (screen, label) => {
    const tab = managementTabs(screen).find((node) => screen.text(node).trim().startsWith(label))
    assert.ok(tab, `관리 탭을 찾을 수 없습니다: ${label}`)
    tab.props.onClick()
    await screen.settle()
}

const operationRows = (screen) =>
    screen
        .all()
        .filter(
            (node) => node.type === 'tr' && screen.all(node).some((child) => child.type === 'button' && /상세 보기|결과 다시 확인/.test(screen.text(child)))
        )
const historySearchInput = (screen) => screen.all().find((node) => node.type === 'input' && node.props['data-label'] === '작업 이력 검색')
const submitHistorySearch = async (screen, query) => {
    const input = historySearchInput(screen)
    assert.ok(input)
    input.props.onInput({ target: { value: query } })
    await screen.settle()
    const form = screen.all().find((node) => node.type === 'form' && screen.all(node).includes(input))
    assert.ok(form, '검색 입력은 제출할 수 있는 폼에 있어야 합니다.')
    assert.equal(screen.button('검색').props.type, 'submit')
    form.props.onSubmit({ preventDefault() {} })
    await screen.settle()
}

test('학습 탭 없이 실행 이력을 표로 표시하고 완료 결과는 상세에서 조회한다', async () => {
    let listings = 0,
        trainingListings = 0
    const details = []
    const completed = {
        ...trainingOperation,
        id: completedTraining.id,
        status: 'SUCCEEDED',
        progress: 100,
        metrics: {
            trainingSamples: 7,
            validationSamples: 2,
            testSamples: 0,
            excludedTestSamples: 1,
            finalTrainingLoss: 1.4747,
            validationLossBefore: 0.6343,
            validationLossAfter: 0.6335,
            adapterTensors: 6,
            reloadMaxLogitDifference: 0,
            qualityEvaluated: false,
            independentTestExecuted: false,
        },
    }
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => {
            trainingListings += 1
            return { jobs: [trainingOperation, completed] }
        },
        getModelOperations: async () => {
            listings += 1
            return operationPage([trainingOperation, completed])
        },
        getModelOperation: async (id) => {
            details.push(id)
            return id === completed.id ? completed : trainingOperation
        },
    })
    try {
        const labels = managementTabs(screen).map((node) => screen.text(node).trim().replace(/\s+/g, ' '))
        assert.deepEqual(labels, ['모델 버전 1', '배포 이력 0', '실행 작업 이력'])
        assert.equal(listings, 0)
        await selectManagementTab(screen, '실행 작업 이력')
        assert.equal(listings, 1)
        assert.equal(trainingListings, 0)
        assert.equal(operationRows(screen).length, 2)
        assert.ok(operationRows(screen).every((row) => /qwen3\.8:27b/.test(screen.text(row))))
        assert.ok(!screen.all().some((node) => node.type === 'article'))
        assert.match(screen.text(), /학습 중/)
        assert.match(screen.text(), /완료/)
        assert.doesNotMatch(screen.text(), /1\.4747|0\.6343|0\.6335/)
        const completedDetail = screen.all(operationRows(screen)[1]).find((node) => node.type === 'button' && screen.text(node).trim() === '상세 보기')
        completedDetail.props.onClick()
        await screen.settle()
        assert.deepEqual(details, [completed.id])
        const dialog = screen.all().find((node) => node.props.role === 'dialog' && node.props['aria-label'] === '실행 작업 상세')
        assert.ok(dialog)
        assert.match(screen.text(dialog), /100%/)
        assert.match(screen.text(dialog), /1\.4747/)
        assert.match(screen.text(dialog), /0\.6343/)
        assert.match(screen.text(dialog), /0\.6335/)
    } finally {
        screen.cleanup()
    }
})

test('실행 이력은 서버에서 열 건씩 조회하고 다음과 이전 페이지에서 필요한 행만 표시한다', async () => {
    const queries = []
    const operations = Array.from({ length: 12 }, (_, index) => ({
        id: `history-${index + 1}`,
        action: 'MODEL_PROBE',
        status: 'PASSED',
        afterModel: `page-model-${index + 1}`,
    }))
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: async (query) => {
            queries.push(query)
            return operationPage(operations.slice(query.page * 10, (query.page + 1) * 10), { page: query.page, totalCount: 12, totalPages: 2 })
        },
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        assert.deepEqual(queries, [{ page: 0, size: 10, query: '', status: '' }])
        assert.equal(operationRows(screen).length, 10)
        assert.match(screen.text(), /1\s*\/\s*2/)
        assert.equal(screen.button('이전').props.disabled, true)
        screen.button('다음').props.onClick()
        await screen.settle()
        assert.deepEqual(queries[1], { page: 1, size: 10, query: '', status: '' })
        assert.equal(operationRows(screen).length, 2)
        assert.match(screen.text(), /page-model-11/)
        assert.doesNotMatch(screen.text(), /page-model-1(?:\s|$)/)
        assert.match(screen.text(), /2\s*\/\s*2/)
        assert.equal(screen.button('다음').props.disabled, true)
        screen.button('이전').props.onClick()
        await screen.settle()
        assert.deepEqual(queries[2], { page: 0, size: 10, query: '', status: '' })
        assert.equal(operationRows(screen).length, 10)
    } finally {
        screen.cleanup()
    }
})

test('검색은 입력할 때 조회하지 않고 제출할 때 첫 페이지부터 서버 전체 이력을 검색한다', async () => {
    const queries = []
    const operations = Array.from({ length: 11 }, (_, index) => ({ id: `history-${index + 1}`, action: 'MODEL_PROBE', status: 'PASSED' }))
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: async (query) => {
            queries.push(query)
            return query.query
                ? operationPage([{ id: 'server-result', action: 'MODEL_ACTIVATE', status: 'APPLIED', afterModel: 'server-selected-model' }])
                : operationPage(operations.slice(query.page * 10, (query.page + 1) * 10), { page: query.page, totalCount: 11, totalPages: 2 })
        },
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        screen.button('다음').props.onClick()
        await screen.settle()
        const input = historySearchInput(screen)
        input.props.onInput({ target: { value: '  검증된 모델  ' } })
        await screen.settle()
        assert.equal(queries.length, 2)
        assert.equal(operationRows(screen).length, 1)
        await submitHistorySearch(screen, '  검증된 모델  ')
        assert.deepEqual(queries[2], { page: 0, size: 10, query: '검증된 모델', status: '' })
        assert.equal(operationRows(screen).length, 1)
        assert.match(screen.text(), /server-selected-model/)
        assert.equal(screen.button('다음').props.disabled, true)
        await submitHistorySearch(screen, '')
        assert.deepEqual(queries[3], { page: 0, size: 10, query: '', status: '' })
        assert.equal(operationRows(screen).length, 10)
    } finally {
        screen.cleanup()
    }
})

test('상태 필터를 바꾸면 검색어를 유지하며 서버의 첫 페이지를 다시 조회한다', async () => {
    const queries = []
    const operations = Array.from({ length: 10 }, (_, index) => ({ id: `history-${index + 1}`, action: 'MODEL_PROBE', status: 'PASSED' }))
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: async (query) => {
            queries.push(query)
            return query.status ? operationPage([]) : operationPage(operations, { page: query.page, totalCount: 20, totalPages: 2 })
        },
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        await submitHistorySearch(screen, '대상 모델')
        screen.button('다음').props.onClick()
        await screen.settle()
        const filter = screen.all().find((node) => node.type === 'select' && node.props['data-label'] === '작업 상태')
        assert.ok(filter)
        filter.props.onChange({ target: { value: 'FAILED' } })
        await screen.settle()
        assert.deepEqual(queries.at(-1), { page: 0, size: 10, query: '대상 모델', status: 'FAILED' })
        assert.equal(operationRows(screen).length, 0)
        assert.match(screen.text(), /검색 조건에 맞는 실행 작업이 없습니다\./)
        assert.equal(screen.button('이전').props.disabled, true)
        assert.equal(screen.button('다음').props.disabled, true)
    } finally {
        screen.cleanup()
    }
})

test('서버가 줄어든 이력의 마지막 페이지로 보정하면 반환한 페이지 번호를 표시한다', async () => {
    const queries = []
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: async (query) => {
            queries.push(query)
            return operationPage([{ id: 'last-result', action: 'MODEL_PROBE', status: 'PASSED' }], {
                page: 0,
                totalCount: queries.length === 1 ? 11 : 1,
                totalPages: queries.length === 1 ? 2 : 1,
            })
        },
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        screen.button('다음').props.onClick()
        await screen.settle()
        assert.equal(queries[1].page, 1)
        assert.match(screen.text(), /1\s*\/\s*1/)
        assert.equal(screen.button('이전').props.disabled, true)
        assert.equal(screen.button('다음').props.disabled, true)
        assert.equal(operationRows(screen).length, 1)
    } finally {
        screen.cleanup()
    }
})

test('진행 중인 작업의 자동 조회는 현재 검색 페이지 열 건으로 제한하고 화면이 숨겨지면 멈춘다', async (context) => {
    context.mock.timers.enable({ apis: ['setTimeout'] })
    const queries = []
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: async (query) => {
            queries.push(query)
            const operations =
                query.page === 0
                    ? [trainingOperation, ...Array.from({ length: 9 }, (_, index) => ({ id: `earlier-${index}`, action: 'MODEL_PROBE', status: 'PASSED' }))]
                    : [trainingOperation]
            return operationPage(operations, { page: query.page, totalCount: 11, totalPages: 2 })
        },
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        await submitHistorySearch(screen, 'qwen')
        screen.button('다음').props.onClick()
        await screen.settle()
        const beforePolling = queries.length
        context.mock.timers.tick(5000)
        await screen.settle()
        assert.equal(queries.length, beforePolling + 1)
        assert.deepEqual(queries.at(-1), { page: 1, size: 10, query: 'qwen', status: '' })
        assert.equal(operationRows(screen).length, 1)
        await screen.setHidden(true)
        const beforeHidden = queries.length
        context.mock.timers.tick(15000)
        await screen.settle()
        assert.equal(queries.length, beforeHidden)
        await screen.setHidden(false)
        assert.equal(queries.length, beforeHidden + 1)
        assert.deepEqual(queries.at(-1), { page: 1, size: 10, query: 'qwen', status: '' })
    } finally {
        screen.cleanup()
    }
})

test('페이지 정보를 보내지 않는 이전 서버에는 재시작 안내를 표시하고 전체 이력을 대신 노출하지 않는다', async () => {
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: async () => ({ operations: [trainingOperation], limit: 100 }),
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        assert.match(screen.text(), /백엔드를 재시작한 뒤 다시 조회/)
        assert.equal(operationRows(screen).length, 0)
        assert.ok(!screen.all().some((node) => node.type === 'button' && screen.text(node).trim() === '취소 요청'))
    } finally {
        screen.cleanup()
    }
})

test('검색 응답이 늦게 돌아와도 더 최근에 제출한 조건의 목록과 페이지를 유지한다', async () => {
    const pending = new Map()
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: (query) => (query.query ? new Promise((resolve) => pending.set(query.query, resolve)) : Promise.resolve(operationPage([]))),
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        await submitHistorySearch(screen, '첫 검색')
        await submitHistorySearch(screen, '다음 검색')
        assert.ok(pending.has('첫 검색'))
        assert.ok(pending.has('다음 검색'))
        pending.get('다음 검색')(operationPage([{ id: 'new-result', action: 'MODEL_PROBE', status: 'PASSED', afterModel: 'new-result-model' }]))
        await screen.settle()
        assert.match(screen.text(), /new-result-model/)
        pending.get('첫 검색')(
            operationPage([{ id: 'old-result', action: 'MODEL_PROBE', status: 'FAILED', afterModel: 'old-result-model' }], { totalCount: 21, totalPages: 3 })
        )
        await screen.settle()
        assert.match(screen.text(), /new-result-model/)
        assert.doesNotMatch(screen.text(), /old-result-model/)
        assert.match(screen.text(), /1\s*\/\s*1/)
    } finally {
        screen.cleanup()
    }
})

test('검색 조회 실패 뒤 남은 이전 목록으로 상세 조회와 학습 취소를 실행하지 않는다', async () => {
    let details = 0,
        cancellations = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: async (query) => {
            if (query.query) throw serverError('검색한 작업 이력을 조회하지 못했습니다.')
            return operationPage([trainingOperation])
        },
        getModelOperation: async () => {
            details += 1
            return trainingOperation
        },
        cancelModelTraining: async () => {
            cancellations += 1
            return { status: 'SUCCEEDED' }
        },
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        const priorDetail = screen.button('상세 보기')
        const priorCancel = screen.button('취소 요청')
        await submitHistorySearch(screen, '연결 실패')
        assert.match(screen.text(), /검색한 작업 이력을 조회하지 못했습니다/)
        for (const button of screen.all().filter((node) => node.type === 'button' && ['상세 보기', '취소 요청'].includes(screen.text(node).trim())))
            assert.equal(button.props.disabled, true)
        priorDetail.props.onClick()
        priorCancel.props.onClick()
        await screen.settle()
        assert.equal(details, 0)
        assert.equal(cancellations, 0)
        assert.ok(!screen.all().some((node) => node.props.role === 'dialog'))
    } finally {
        screen.cleanup()
    }
})

test('실행 이력의 학습 취소는 대상 확인 전 호출하지 않고 확인 뒤 중복 클릭해도 한 번만 호출한다', async () => {
    const cancellations = []
    let finishCancellation
    let cancelled = false
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: async () => operationPage([{ ...trainingOperation, status: cancelled ? 'CANCELLED' : 'TRAINING' }]),
        cancelModelTraining: (id, body) => {
            cancellations.push({ id, body })
            return new Promise((resolve) => {
                finishCancellation = () => {
                    cancelled = true
                    resolve({ status: 'SUCCEEDED', message: '학습 취소 요청을 확인했습니다.' })
                }
            })
        },
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        screen.button('취소 요청').props.onClick()
        await screen.settle()
        assert.match(screen.text(), new RegExp(trainingOperation.id))
        assert.equal(screen.button('확인 후 취소 요청').props.disabled, true)
        screen.button('확인 후 취소 요청').props.onClick()
        await screen.settle()
        assert.equal(cancellations.length, 0)
        await checkConfirmation(screen)
        const submit = screen.button('확인 후 취소 요청')
        submit.props.onClick()
        submit.props.onClick()
        await screen.settle()
        assert.equal(cancellations.length, 1)
        assert.equal(cancellations[0].id, trainingOperation.id)
        assert.match(cancellations[0].body.operationId, /^[0-9a-f-]{36}$/)
        finishCancellation()
        await screen.settle()
        assert.match(screen.text(), /취소됨/)
    } finally {
        screen.cleanup()
    }
})

test('조회 권한만 있으면 학습 진행률과 완료 결과를 보되 취소 요청은 표시하지 않는다', async () => {
    let cancellations = 0
    const screen = await mountScreen(
        {
            getModelRegistry: async () => registry,
            getModelRuntime: async () => runtime,
            getModelOperations: async () => operationPage([trainingOperation]),
            cancelModelTraining: async () => {
                cancellations += 1
            },
        },
        ['MODEL_READ']
    )
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        assert.match(screen.text(), /45%/)
        assert.ok(!screen.all().some((node) => node.type === 'button' && screen.text(node).trim() === '취소 요청'))
        assert.equal(cancellations, 0)
    } finally {
        screen.cleanup()
    }
})

for (const uncertain of ['UNKNOWN', 'NETWORK']) {
    test(`학습 취소 ${uncertain === 'UNKNOWN' ? '결과 미확정' : '통신 실패'} 뒤 탭을 이동해도 같은 요청 식별자로만 수동 재시도한다`, async () => {
        const cancellations = []
        const screen = await mountScreen({
            getModelRegistry: async () => registry,
            getModelRuntime: async () => runtime,
            getModelOperations: async () => operationPage([trainingOperation]),
            cancelModelTraining: async (id, body) => {
                cancellations.push({ id, body })
                if (uncertain === 'NETWORK') throw new Error('응답 연결이 끊어졌습니다.')
                return { status: 'UNKNOWN' }
            },
        })
        try {
            await selectManagementTab(screen, '실행 작업 이력')
            screen.button('취소 요청').props.onClick()
            await screen.settle()
            await checkConfirmation(screen)
            screen.button('확인 후 취소 요청').props.onClick()
            await screen.settle()
            assert.equal(cancellations.length, 1)
            await selectManagementTab(screen, '모델 버전')
            await selectManagementTab(screen, '실행 작업 이력')
            assert.equal(cancellations.length, 1)
            screen.button('같은 요청 수동 재시도').props.onClick()
            await screen.settle()
            assert.equal(cancellations.length, 2)
            assert.equal(cancellations[0].id, trainingOperation.id)
            assert.deepEqual(cancellations[0], cancellations[1])
        } finally {
            screen.cleanup()
        }
    })
}

test('모델 작업의 학습 화면에서 실행 이력으로 이동하면 모달을 닫고 진행 상황을 표시한다', async () => {
    let listings = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: async () => {
            listings += 1
            return operationPage([trainingOperation])
        },
    })
    try {
        screen.button('모델 작업').props.onClick()
        await screen.settle()
        screen.button('선택 모델 학습 승인 자료로 시험 학습').props.onClick()
        await screen.settle()
        assert.equal(listings, 0)
        screen.button('실행 작업 이력 보기').props.onClick()
        await screen.settle()
        assert.ok(!screen.all().some((node) => node.props.role === 'dialog' && node.props['aria-label'] === '모델 작업'))
        const tab = managementTabs(screen).find((node) => screen.text(node).trim() === '실행 작업 이력')
        assert.equal(tab.props['aria-pressed'], true)
        assert.equal(listings, 1)
        assert.match(screen.text(), /45%/)
    } finally {
        screen.cleanup()
    }
})

test('같은 모델의 완료 학습으로만 새 모델을 만들고 확인 전에는 GPU 작업을 요청하지 않는다', async () => {
    const calls = [],
        queries = []
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async (model) => {
            queries.push(model)
            return {
                jobs: [
                    completedTraining,
                    { ...completedTraining, id: 'other-result', target: { sourceModel: 'other:model' } },
                    { ...completedTraining, id: 'unfinished', status: 'TRAINING' },
                ],
            }
        },
        startTrainedModelBuild: async (id) => {
            calls.push(id)
            return { jobId: id, sourceModel: 'qwen3.8:27b', status: 'FUSING', progress: 15, available: false, message: '학습 서버에서 병합하고 있습니다.' }
        },
    })
    try {
        await openAdapterAction(screen)
        assert.ok(queries.every((model) => model === 'qwen3.8:27b'))
        assert.doesNotMatch(screen.text(), /other-result|unfinished/)
        assert.equal(screen.button('분석 서버에 새 모델 등록').props.disabled, true)
        screen.button('새 학습 모델 생성').props.onClick()
        await screen.settle()
        assert.equal(screen.button('확인 후 새 모델 생성').props.disabled, true)
        assert.equal(calls.length, 0)
        assert.match(screen.text(), /학습 서버에서 완료한 학습 결과를 기반 모델과 합치고/)
        await checkConfirmation(screen)
        const submit = screen.button('확인 후 새 모델 생성')
        submit.props.onClick()
        submit.props.onClick()
        await screen.settle()
        assert.deepEqual(calls, [completedTraining.id])
        assert.match(screen.text(), /병합하고 있습니다/)
        assert.equal(screen.button('새 학습 모델 생성').props.disabled, true)
        assert.equal(screen.button('모델 생성 취소').props.disabled, false)
        assert.equal(screen.button('분석 서버에 새 모델 등록').props.disabled, true)
    } finally {
        screen.cleanup()
    }
})

test('완성한 새 모델을 분석 서버에 등록하고 검증 뒤 별도 확인을 받아 적용한다', async () => {
    let registered = false,
        active = false
    const imports = [],
        probes = [],
        activations = []
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getInstalledModels: async () => ({
            model: active ? preparedModel.model : 'qwen3.8:27b',
            currentRevision: active ? 8 : 7,
            models: [
                { name: 'qwen3.8:27b', digest: 'b'.repeat(64), selectable: true },
                ...(registered
                    ? [
                          {
                              name: preparedModel.model,
                              digest: preparedModel.digest,
                              selectable: true,
                              trainedModel: true,
                              provider: 'ollama',
                              trainingJobId: completedTraining.id,
                              sourceModel: 'qwen3.8:27b',
                          },
                      ]
                    : []),
            ],
        }),
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', progress: 100, available: true }),
        getTrainedModelImport: async () => (registered ? preparedModel : { ...preparedModel, status: 'NOT_STARTED' }),
        importTrainedModel: async (id) => {
            imports.push(id)
            registered = true
            return preparedModel
        },
        probeInstalledModel: async (body) => {
            probes.push(body)
            return { ...probeResult, model: preparedModel.model, digest: preparedModel.digest }
        },
        activateInstalledModel: async (body) => {
            activations.push(body)
            active = true
            return { status: 'APPLIED', message: '새 학습 모델을 적용했습니다.' }
        },
    })
    try {
        await openAdapterAction(screen)
        assert.equal(screen.button('새 학습 모델 생성').props.disabled, true)
        screen.button('분석 서버에 새 모델 등록').props.onClick()
        await screen.settle()
        assert.equal(imports.length, 0)
        await checkConfirmation(screen)
        screen.button('확인 후 새 모델 등록').props.onClick()
        await screen.settle()
        assert.deepEqual(imports, [completedTraining.id])
        assert.equal(activations.length, 0)
        assert.equal(screen.button('새 학습 모델 적용').props.disabled, true)
        screen.button('새 모델 응답 검증').props.onClick()
        await screen.settle()
        assert.equal(probes[0].model, preparedModel.model)
        assert.equal(probes[0].digest, preparedModel.digest)
        screen.button('새 학습 모델 적용').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /분석 서버의 기존 모델 실행 환경/)
        assert.doesNotMatch(screen.text(), /분석 서버의 MLX/)
        assert.equal(activations.length, 0)
        await checkConfirmation(screen)
        screen.button('확인 후 새 모델 적용').props.onClick()
        await screen.settle()
        assert.equal(activations.length, 1)
        assert.equal(activations[0].model, preparedModel.model)
        assert.equal(activations[0].probeToken, probeResult.probeToken)
        assert.equal(activations[0].expectedRevision, 7)
    } finally {
        screen.cleanup()
    }
})

test('등록 응답을 기다리는 동안 즉시 안내하고 중복 요청을 보내지 않는다', async () => {
    let finishImport,
        calls = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', available: true }),
        importTrainedModel: () => {
            calls += 1
            return new Promise((resolve) => (finishImport = resolve))
        },
    })
    try {
        await openAdapterAction(screen)
        screen.button('분석 서버에 새 모델 등록').props.onClick()
        await screen.settle()
        await checkConfirmation(screen)
        const submit = screen.button('확인 후 새 모델 등록')
        submit.props.onClick()
        submit.props.onClick()
        await screen.settle()
        assert.equal(calls, 1)
        assert.match(screen.text(), /등록 요청을 보내고 있습니다/)
        assert.match(screen.text(), /서버의 접수 응답을 기다리고 있습니다/)
        assert.ok(
            screen
                .all()
                .filter((node) => node.type === 'button' && screen.text(node).trim() === '등록 요청 확인 중')
                .every((node) => node.props.disabled)
        )
        finishImport({ ...preparedModel, status: 'IMPORTING', phase: 'DOWNLOADING', message: '모델 파일이 준비되기를 기다립니다.' })
        await screen.settle()
        assert.match(screen.text(), /모델 파일 준비 대기/)
        assert.match(screen.text(), /마지막 상태 확인/)
        assert.ok(!screen.all().some((node) => node.props.role === 'dialog' && node.props['aria-label'] === '분석 서버 등록 확인'))
    } finally {
        screen.cleanup()
    }
})

test('등록 단계를 자동 조회하고 반복 검증을 현재 단계로 표시하며 완료 뒤 조회를 멈춘다', async (context) => {
    context.mock.timers.enable({ apis: ['setTimeout'] })
    const phases = ['VERIFYING', 'COPYING', 'VERIFYING', 'REGISTERING', 'READY']
    let requested = false,
        registered = false,
        queries = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', available: true }),
        getTrainedModelImport: async () => {
            queries += 1
            if (!requested) return { ...preparedModel, status: 'NOT_STARTED' }
            if (registered) return preparedModel
            const phase = phases.shift()
            registered = phase === 'READY'
            return registered ? preparedModel : { ...preparedModel, status: 'IMPORTING', phase, message: '서버가 현재 단계를 처리하고 있습니다.' }
        },
        getInstalledModels: async () => ({
            model: 'qwen3.8:27b',
            models: [
                { name: 'qwen3.8:27b', selectable: true },
                ...(registered ? [{ name: preparedModel.model, digest: preparedModel.digest, selectable: true, trainedModel: true }] : []),
            ],
        }),
        importTrainedModel: async () => {
            requested = true
            return {
                ...preparedModel,
                status: 'IMPORTING',
                phase: 'DOWNLOADING',
                message: '공유 저장소에 다운로드를 요청했습니다. 파일이 준비되기를 기다립니다.',
            }
        },
    })
    try {
        await openAdapterAction(screen)
        screen.button('분석 서버에 새 모델 등록').props.onClick()
        await screen.settle()
        await checkConfirmation(screen)
        screen.button('확인 후 새 모델 등록').props.onClick()
        await screen.settle()
        const currentStep = () => screen.all().filter((node) => node.props['aria-current'] === 'step')
        assert.equal(currentStep().length, 1)
        assert.match(screen.text(currentStep()[0]), /모델 파일 다운로드/)
        assert.doesNotMatch(screen.text(), /\d+%/)
        for (const label of ['파일 검증', '로컬 보관', '파일 검증', '실행 엔진 등록']) {
            context.mock.timers.tick(5000)
            await screen.settle()
            assert.equal(currentStep().length, 1)
            assert.match(screen.text(currentStep()[0]), new RegExp(label))
        }
        context.mock.timers.tick(5000)
        await screen.settle()
        assert.match(screen.text(), /분석 서버 등록 완료/)
        assert.match(screen.text(), /아래에서 새 모델의 응답을 검증한 뒤 적용/)
        assert.equal(screen.button('새 학습 모델 적용').props.disabled, true)
        const completedQueries = queries
        context.mock.timers.tick(15000)
        await screen.settle()
        assert.equal(queries, completedQueries)
        assert.equal(phases.length, 0)
    } finally {
        screen.cleanup()
    }
})

test('세부 단계를 보내지 않는 서버 응답에는 확인 가능한 범위를 명확히 표시한다', async () => {
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', available: true }),
        getTrainedModelImport: async () => ({ ...preparedModel, status: 'IMPORTING', message: '등록을 처리하고 있습니다.' }),
    })
    try {
        await openAdapterAction(screen)
        assert.match(screen.text(), /서버가 세부 진행 단계를 제공하지 않아 현재 작업 위치는 확인할 수 없습니다/)
        assert.equal(screen.all().filter((node) => node.props['aria-current'] === 'step').length, 0)
        assert.equal(screen.button('분석 서버에 새 모델 등록').props.disabled, true)
    } finally {
        screen.cleanup()
    }
})

test('진행 상태 조회가 실패하면 마지막 확인 상태와 복구 버튼을 표시하고 자동 조회를 멈춘다', async (context) => {
    context.mock.timers.enable({ apis: ['setTimeout'] })
    let queries = 0,
        recovered = false
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', available: true }),
        getTrainedModelImport: async () => {
            queries += 1
            if (queries === 1 || recovered) return { ...preparedModel, status: 'IMPORTING', phase: recovered ? 'COPYING' : 'DOWNLOADING' }
            throw serverError('등록 상태 연결이 끊겼습니다.')
        },
    })
    try {
        await openAdapterAction(screen)
        context.mock.timers.tick(5000)
        await screen.settle()
        assert.match(screen.text(), /등록 상태 확인이 중단되었습니다/)
        assert.match(screen.text(), /마지막으로 확인한 상태/)
        assert.match(screen.text(), /등록 상태 연결이 끊겼습니다/)
        assert.match(screen.text(), /마지막 확인 단계/)
        assert.doesNotMatch(screen.text(), /5초마다 상태를 확인/)
        context.mock.timers.tick(15000)
        await screen.settle()
        assert.equal(queries, 2)
        recovered = true
        screen.button('등록 상태 다시 확인').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /분석 서버에 모델 파일 보관 중/)
        assert.doesNotMatch(screen.text(), /등록 상태 확인이 중단되었습니다|등록 상태 연결이 끊겼습니다/)
        assert.equal(queries, 3)
    } finally {
        screen.cleanup()
    }
})

test('서버가 연결 오류를 미확인 상태로 반환해도 재등록을 막고 상태 재조회를 제공한다', async (context) => {
    context.mock.timers.enable({ apis: ['setTimeout'] })
    let queries = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', available: true }),
        getTrainedModelImport: async () => {
            queries += 1
            return queries === 2
                ? { ...preparedModel, status: 'UNKNOWN', errorCode: 'REMOTE_UNREACHABLE', message: 'AI 분석/추론 서버에 연결하지 못했습니다.' }
                : { ...preparedModel, status: 'IMPORTING', phase: 'DOWNLOADING' }
        },
    })
    try {
        await openAdapterAction(screen)
        context.mock.timers.tick(5000)
        await screen.settle()
        assert.match(screen.text(), /등록 결과 확인 필요/)
        assert.match(screen.text(), /AI 분석\/추론 서버에 연결하지 못했습니다/)
        assert.doesNotMatch(screen.text(), /등록 실패/)
        assert.equal(screen.button('분석 서버에 새 모델 등록').props.disabled, true)
        context.mock.timers.tick(15000)
        await screen.settle()
        assert.equal(queries, 2)
        screen.button('등록 상태 다시 확인').props.onClick()
        await screen.settle()
        assert.equal(queries, 3)
        assert.match(screen.text(), /모델 파일 준비 대기/)
    } finally {
        screen.cleanup()
    }
})

test('등록 요청의 응답이 끊기면 중복 등록 대신 상태를 다시 확인할 수 있다', async () => {
    let attempted = false,
        calls = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', available: true }),
        getTrainedModelImport: async () => ({
            ...preparedModel,
            status: attempted ? 'IMPORTING' : 'NOT_STARTED',
            phase: attempted ? 'DOWNLOADING' : undefined,
        }),
        importTrainedModel: async () => {
            attempted = true
            calls += 1
            throw new Error('응답 시간 초과')
        },
    })
    try {
        await openAdapterAction(screen)
        screen.button('분석 서버에 새 모델 등록').props.onClick()
        await screen.settle()
        await checkConfirmation(screen)
        screen.button('확인 후 새 모델 등록').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /등록 요청 결과 확인 필요/)
        assert.match(screen.text(), /서버가 접수했을 수 있으므로/)
        assert.equal(screen.button('분석 서버에 새 모델 등록').props.disabled, true)
        screen.button('등록 상태 다시 확인').props.onClick()
        await screen.settle()
        assert.equal(calls, 1)
        assert.match(screen.text(), /모델 파일 준비 대기/)
        assert.doesNotMatch(screen.text(), /등록 요청 결과 확인 필요/)
    } finally {
        screen.cleanup()
    }
})

test('등록 응답 전에 화면을 숨겼다 돌아와도 중복 등록을 막고 서버 상태를 다시 조회한다', async () => {
    let finishImport,
        requested = false,
        calls = 0,
        queries = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', available: true }),
        getTrainedModelImport: async () => {
            queries += 1
            return { ...preparedModel, status: requested ? 'IMPORTING' : 'NOT_STARTED', phase: requested ? 'COPYING' : undefined }
        },
        importTrainedModel: () => {
            calls += 1
            requested = true
            return new Promise((resolve) => (finishImport = resolve))
        },
    })
    try {
        await openAdapterAction(screen)
        screen.button('분석 서버에 새 모델 등록').props.onClick()
        await screen.settle()
        await checkConfirmation(screen)
        screen.button('확인 후 새 모델 등록').props.onClick()
        await screen.settle()
        await screen.setHidden(true)
        await screen.setHidden(false)
        finishImport({ ...preparedModel, status: 'IMPORTING', phase: 'DOWNLOADING' })
        await screen.settle()
        assert.equal(calls, 1)
        assert.ok(queries >= 2)
        assert.match(screen.text(), /분석 서버에 모델 파일 보관 중/)
        assert.equal(screen.button('분석 서버에 새 모델 등록').props.disabled, true)
        assert.ok(!screen.all().some((node) => node.props.role === 'dialog' && node.props['aria-label'] === '분석 서버 등록 확인'))
    } finally {
        screen.cleanup()
    }
})

test('등록 완료 뒤 설치 목록 갱신이 실패해도 완료 결과를 보존하고 목록만 다시 조회한다', async () => {
    let reloads = 0,
        changes = 0
    const screen = await mountScreen(
        {
            getModelTraining: async () => ({ jobs: [completedTraining] }),
            getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', available: true }),
            getTrainedModelImport: async () => ({ ...preparedModel, historySyncRequired: true }),
            importTrainedModel: async () => {
                throw new Error('완료한 모델을 다시 등록하면 안 됩니다.')
            },
        },
        null,
        {
            component: TrainedModelPanel,
            props: {
                modelName: 'qwen3.8:27b',
                visible: true,
                available: true,
                canDeploy: true,
                canTrain: true,
                onChanged: () => {
                    changes += 1
                },
                reload: async () => {
                    reloads += 1
                    if (reloads === 1) throw new Error('설치 목록 연결 실패')
                },
            },
        }
    )
    try {
        screen
            .all()
            .find((node) => node.props['data-label'] === '완료한 학습 결과')
            .props.onChange({ target: { value: completedTraining.id } })
        await screen.settle()
        assert.match(screen.text(), /분석 서버 등록 완료/)
        assert.match(screen.text(), /새 모델 등록은 완료했지만 설치 목록을 갱신하지 못했습니다/)
        assert.match(screen.text(), /모델 등록은 완료했지만 버전 기록을 저장하지 못했습니다/)
        assert.match(screen.text(), /모델을 다시 등록할 필요는 없습니다/)
        assert.equal(changes, 1)
        assert.equal(reloads, 1)
        screen.button('설치 목록 다시 확인').props.onClick()
        await screen.settle()
        assert.equal(reloads, 2)
        assert.equal(changes, 1)
        assert.match(screen.text(), /분석 서버 등록 완료/)
        assert.doesNotMatch(screen.text(), /설치 목록을 갱신하지 못했습니다/)
    } finally {
        screen.cleanup()
    }
})

test('완료 모델의 목록을 갱신하다 화면을 숨겨도 요청 중 잠금이 남지 않는다', async () => {
    let finishReload,
        reloads = 0
    const busy = []
    const screen = await mountScreen(
        {
            getModelTraining: async () => ({ jobs: [completedTraining] }),
            getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', available: true }),
            getTrainedModelImport: async () => preparedModel,
        },
        null,
        {
            component: TrainedModelPanel,
            props: {
                modelName: 'qwen3.8:27b',
                visible: true,
                available: true,
                canDeploy: true,
                canTrain: true,
                onBusy: (value) => busy.push(value),
                reload: async () => {
                    reloads += 1
                    if (reloads === 1) await new Promise((resolve) => (finishReload = resolve))
                },
            },
        }
    )
    try {
        screen
            .all()
            .find((node) => node.props['data-label'] === '완료한 학습 결과')
            .props.onChange({ target: { value: completedTraining.id } })
        await screen.settle()
        assert.equal(busy.at(-1), true)
        await screen.setHidden(true)
        finishReload()
        await screen.settle()
        assert.equal(busy.at(-1), false)
        await screen.setHidden(false)
        assert.match(screen.text(), /분석 서버 등록 완료/)
        assert.equal(busy.at(-1), false)
    } finally {
        screen.cleanup()
    }
})

test('같은 학습 결과의 늦은 조회 응답이 최신 등록 단계로 되돌아가지 않게 한다', async () => {
    let finishOldStatus,
        queries = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'SUCCEEDED', available: true }),
        getTrainedModelImport: async () => {
            queries += 1
            if (queries === 1) return new Promise((resolve) => (finishOldStatus = resolve))
            return { ...preparedModel, status: 'IMPORTING', phase: 'COPYING' }
        },
    })
    try {
        await openAdapterAction(screen)
        screen.button('준비 상태 새로고침').props.onClick()
        await screen.settle()
        assert.match(screen.text(), /분석 서버에 모델 파일 보관 중/)
        finishOldStatus({ ...preparedModel, status: 'IMPORTING', phase: 'DOWNLOADING' })
        await screen.settle()
        assert.match(screen.text(), /분석 서버에 모델 파일 보관 중/)
        assert.doesNotMatch(screen.text(), /모델 파일 준비 대기/)
        assert.equal(queries, 2)
    } finally {
        screen.cleanup()
    }
})

test('새 모델 생성 중에는 진행률과 확인 후 취소를 제공하고 학습 원본에는 취소를 보내지 않는다', async () => {
    const cancellations = []
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({ jobId: completedTraining.id, sourceModel: 'qwen3.8:27b', status: 'CONVERTING', progress: 65, available: false }),
        cancelTrainedModelBuild: async (id) => {
            cancellations.push(id)
            return { jobId: id, sourceModel: 'qwen3.8:27b', status: 'CANCELLED', available: true }
        },
        cancelModelTraining: async () => {
            throw new Error('학습 원본에 취소를 보내면 안 됩니다.')
        },
    })
    try {
        await openAdapterAction(screen)
        assert.match(screen.text(), /65%/)
        screen.button('모델 생성 취소').props.onClick()
        await screen.settle()
        assert.equal(cancellations.length, 0)
        await checkConfirmation(screen)
        screen.button('확인 후 모델 생성 취소').props.onClick()
        await screen.settle()
        assert.deepEqual(cancellations, [completedTraining.id])
        assert.match(screen.text(), /생성 취소/)
    } finally {
        screen.cleanup()
    }
})

test('학습 서버 변환 환경이 준비되지 않았거나 등록 응답이 끊기면 자동 실행하지 않는다', async () => {
    let calls = 0
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelTraining: async () => ({ jobs: [completedTraining] }),
        getTrainedModelBuild: async () => ({
            jobId: completedTraining.id,
            sourceModel: 'qwen3.8:27b',
            status: 'NOT_BUILT',
            available: false,
            reasonCode: 'MODEL_BUILD_DISK_SPACE',
        }),
        startTrainedModelBuild: async () => {
            calls += 1
        },
    })
    try {
        await openAdapterAction(screen)
        assert.equal(screen.button('새 학습 모델 생성').props.disabled, true)
        screen.button('새 학습 모델 생성').props.onClick()
        await screen.settle()
        assert.equal(calls, 0)
        assert.match(screen.text(), /MODEL_BUILD_DISK_SPACE/)
    } finally {
        screen.cleanup()
    }
})

test('목록 자동 조회가 실패해도 이미 연 작업 상세는 같은 식별자로 다시 조회한다', async () => {
    const operation = { id: 'pending-detail', action: 'MODEL_ACTIVATE', status: 'UNKNOWN' }
    const details = []
    let listingFails = false
    const screen = await mountScreen({
        getModelRegistry: async () => registry,
        getModelRuntime: async () => runtime,
        getModelOperations: async () => {
            if (listingFails) throw serverError('목록 조회를 완료하지 못했습니다.')
            return operationPage([operation])
        },
        getModelOperation: async (id) => {
            details.push(id)
            return operation
        },
    })
    try {
        await selectManagementTab(screen, '실행 작업 이력')
        screen.button('결과 다시 확인').props.onClick()
        await screen.settle()
        listingFails = true
        screen.button('이력 새로고침').props.onClick()
        await screen.settle()
        assert.equal(screen.button('결과 다시 확인').props.disabled, true)
        screen.button('이 작업 다시 조회').props.onClick()
        await screen.settle()
        assert.deepEqual(details, ['pending-detail', 'pending-detail'])
    } finally {
        screen.cleanup()
    }
})
