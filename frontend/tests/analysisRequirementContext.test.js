import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, shallowRef } from 'vue'

const moduleUrl = (code) => `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
const compile = async (path, replacements = {}) => {
    const source = await readFile(new URL(path, import.meta.url), 'utf8')
    let code = compileScript(parse(source).descriptor, {
        id: path,
        inlineTemplate: true,
        templateOptions: { compilerOptions: { hoistStatic: false } },
    }).content
    for (const match of [...code.matchAll(/from (['"])([^'"]+)\1/g)]) {
        code = code.replaceAll(match[0], `from ${JSON.stringify(replacements[match[2]] || import.meta.resolve(match[2]))}`)
    }
    return moduleUrl(code)
}
const modalModule = await compile('../src/components/base/BaseModal.vue')
const { default: Component } = await import(
    await compile('../src/components/analysis/AnalysisRequirementContext.vue', { '@/components/base/BaseModal.vue': modalModule })
)

// 실제 컴포넌트와 공통 모달을 렌더링하고 기본 대화상자·스크롤만 대체한다.
const makeNode = (type, text = '') => ({
    type,
    text,
    children: [],
    props: {},
    parent: null,
    style: { overflow: '' },
    open: false,
    scrollTop: 0,
    showModal() {
        this.open = true
    },
    close() {
        this.open = false
        this.props.onClose?.()
    },
})
const all = (node) => [node, ...node.children.flatMap(all)]
const textOf = (node) => (node.type === 'comment' ? '' : `${node.text}${node.children.map(textOf).join('')}`)
const hasClass = (node, name) =>
    String(node.props.class || '')
        .split(/\s+/)
        .includes(name)
const findText = (root, type, text) => all(root).find((node) => node.type === type && textOf(node).trim() === text)

const mount = async (initial = {}) => {
    const body = makeNode('body')
    body.style.overflow = 'auto'
    const previousDocument = globalThis.document
    globalThis.document = { body }
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
        querySelector: () => body,
        insert(node, parent, anchor = null) {
            detach(node)
            const index = anchor ? parent.children.indexOf(anchor) : -1
            parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
            node.parent = parent
        },
        remove: detach,
    })
    const root = makeNode('root')
    const props = shallowRef({ context: null, loading: false, error: '', componentKey: 'job-3', ...initial })
    let retries = 0
    const app = renderer.createApp({
        setup: () => () => {
            const { componentKey, ...values } = props.value
            return h('dl', {}, [h(Component, { ...values, key: componentKey, onRetry: () => retries++ })])
        },
    })
    app.mount(root)
    const settle = async () => {
        await nextTick()
        await nextTick()
        await nextTick()
    }
    await settle()
    return {
        root,
        body,
        dialog: () => all(body).find((node) => node.type === 'dialog'),
        retries: () => retries,
        open: async () => {
            all(root)
                .find((node) => node.type === 'button' && node.props['aria-haspopup'] === 'dialog')
                .props.onClick()
            await settle()
        },
        close: async () => {
            findText(body, 'button', '닫기').props.onClick()
            await settle()
        },
        update: async (values) => {
            props.value = { ...props.value, ...values }
            await settle()
        },
        cleanup: () => {
            app.unmount()
            if (previousDocument === undefined) delete globalThis.document
            else globalThis.document = previousDocument
        },
    }
}

test('요청 정보를 기다리는 동안 간결히 알리고 결과 없이도 정보 목록에 요청 내용을 표시한다', async () => {
    const screen = await mount({ loading: true })
    try {
        const label = findText(screen.root, 'dt', '변경 요청 내용')
        assert.ok(label)
        assert.ok(hasClass(label.parent, 'col-span-full'))
        assert.equal(label.parent.parent.type, 'dl')
        assert.match(textOf(all(screen.root).find((node) => node.props.role === 'status')), /불러오는 중/)
        assert.equal(label.parent.props['aria-busy'], true)
        assert.equal(all(screen.root).filter((node) => node.type === 'button').length, 0)
        assert.doesNotMatch(textOf(screen.root), /확인할 수 없습니다/)
        await screen.update({
            loading: false,
            context: { versionId: 'version-3', title: '목표 완료일 추가', versionNumber: 3, originalContent: '변경요청의 목표 완료일을 지정한다.' },
        })
        const title = findText(screen.root, 'button', '목표 완료일 추가')
        assert.equal(title.parent.type, 'dd')
        assert.equal(title.props['aria-haspopup'], 'dialog')
        assert.equal(title.props.title, '분석 요청 상세 보기')
        assert.equal(all(screen.root).filter((node) => node.type === 'button').length, 1)
        assert.equal(all(screen.root).filter((node) => node.type === 'span').length, 0)
        assert.doesNotMatch(textOf(screen.root), /변경요청의 목표 완료일을 지정한다/)
        assert.equal(screen.dialog().open, false)
        assert.equal(all(screen.root).filter((node) => node.props.role === 'status').length, 0)
    } finally {
        screen.cleanup()
    }
})

test('요청 상세를 열면 원문 전체와 없는 내용 안내를 보여주고 닫아도 요청 정보를 유지한다', async () => {
    const context = {
        versionId: 'version-3',
        requirementKey: 'REQ-TARGET-DATE-001',
        title: '목표 완료일 추가',
        versionNumber: '3',
        originalContent: '완료일을 입력하고 저장한다.',
    }
    const screen = await mount({ context })
    try {
        assert.equal(all(screen.root).filter((node) => node.type === 'button').length, 1)
        await screen.open()
        assert.equal(screen.dialog().open, true)
        assert.equal(screen.body.style.overflow, 'hidden')
        assert.ok(findText(screen.body, 'h2', '분석 요청 상세'))
        assert.ok(findText(screen.body, 'span', 'REQ-TARGET-DATE-001'))
        assert.ok(findText(screen.body, 'span', 'v3'))
        assert.ok(findText(screen.body, 'p', context.originalContent))
        assert.match(textOf(screen.body), /저장된 정리 내용이 없습니다/)
        assert.match(textOf(screen.body), /저장된 인수 조건이 없습니다/)
        assert.match(textOf(screen.body), /이 창을 닫아도 진행 중인 분석에는 영향이 없습니다/)
        await screen.close()
        assert.equal(screen.dialog().open, false)
        assert.equal(screen.body.style.overflow, 'auto')
        assert.ok(findText(screen.root, 'button', context.title))
        assert.equal(screen.retries(), 0)
        assert.equal(context.originalContent, '완료일을 입력하고 저장한다.')
    } finally {
        screen.cleanup()
    }
})

test('최신 추가 질문과 요청 내용은 화면 길이를 늘리지 않고 모달 안에 전체 표시한다', async () => {
    const context = {
        versionId: 'version-4',
        title: '목표 완료일 추가',
        versionNumber: 4,
        originalContent: '완료일 추가 요청 원문',
        refinedContent: '완료일 입력과 저장, 목록 표시를 추가한다.',
        latestFollowUpQuestion: '기존에 등록된 변경요청의 완료일은 어떻게 처리하나요?',
    }
    const screen = await mount({ context })
    try {
        assert.doesNotMatch(textOf(screen.root), /완료일 추가 요청 원문|완료일 입력과 저장|기존에 등록된 변경요청/)
        await screen.open()
        const heading = findText(screen.body, 'h4', '최신 추가 질문')
        assert.ok(hasClass(heading.parent, 'bg-blue-50'))
        assert.ok(findText(heading.parent, 'p', context.latestFollowUpQuestion))
        assert.ok(findText(screen.body, 'p', context.originalContent))
        assert.ok(findText(screen.body, 'p', context.refinedContent))
    } finally {
        screen.cleanup()
    }
})

test('자료가 비거나 조회가 실패하면 재시도하고 모르는 제목이나 버전을 만들지 않는다', async () => {
    const screen = await mount({ context: { versionNumber: 0, acceptanceCriteria: [null, {}, '  '] } })
    try {
        assert.match(textOf(screen.root), /저장된 요청 정보를 확인할 수 없습니다/)
        assert.equal(all(screen.root).filter((node) => node.type === 'h3').length, 0)
        const retry = findText(screen.root, 'button', '다시 불러오기')
        retry.props.onClick()
        assert.equal(screen.retries(), 1)
        await screen.update({ loading: true, error: '요청 정보를 불러오지 못했습니다.' })
        assert.equal(findText(screen.root, 'p', '요청 정보를 불러오지 못했습니다.').props.role, 'alert')
        assert.equal(findText(screen.root, 'button', '다시 불러오기').props.disabled, true)
        await screen.update({ loading: false, error: '', context: { originalContent: '이전에 저장된 요청' } })
        assert.ok(findText(screen.root, 'button', '제목이 없는 분석 요청'))
        assert.equal(all(screen.root).filter((node) => node.type === 'span' && /^v\d+$/.test(textOf(node))).length, 0)
    } finally {
        screen.cleanup()
    }
})

test('긴 제목은 줄 수를 제한하고 요청과 질문의 긴 전체 내용은 모달에 보존한다', async () => {
    const title = '아주 긴 변경요청 제목'.repeat(40)
    const originalContent = `요청 원문 ${'가나다라마바사😀'.repeat(80)} 원문 끝`
    const refinedContent = `정리 내용 ${'완료일 검증을 추가한다. '.repeat(60)} 정리 끝`
    const latestFollowUpQuestion = `추가 질문 ${'기존 자료는 어떻게 처리하나요? '.repeat(40)} 질문 끝`
    const screen = await mount({ context: { versionId: 'version-3', title, originalContent, refinedContent, latestFollowUpQuestion } })
    try {
        const heading = findText(screen.root, 'button', title)
        assert.ok(hasClass(heading, 'request-context-title'))
        assert.ok(hasClass(heading, '[overflow-wrap:anywhere]'))
        assert.equal(all(screen.root).filter((node) => node.type === 'p').length, 0)
        await screen.open()
        assert.ok(findText(screen.body, 'h3', title))
        assert.ok(findText(screen.body, 'p', originalContent))
        assert.ok(findText(screen.body, 'p', refinedContent))
        assert.ok(findText(screen.body, 'p', latestFollowUpQuestion))
        assert.doesNotMatch(textOf(screen.body), /…/)
    } finally {
        screen.cleanup()
    }
})

test('중첩 인수 조건을 번호 목록으로 풀어 보여주고 전달된 자료를 바꾸지 않는다', async () => {
    const deepFreeze = (value) => {
        if (value && typeof value === 'object') {
            Object.values(value).forEach(deepFreeze)
            Object.freeze(value)
        }
        return value
    }
    const context = deepFreeze({
        title: '인수 조건 확인',
        acceptanceCriteria: [
            { given: '완료일을 입력하면', then: { actions: ['저장한다', '목록에 표시한다'] } },
            '지난 날짜는 안내한다',
            { settings: { retries: 3, enabled: true, optional: false } },
            null,
            {},
        ],
    })
    const before = JSON.stringify(context)
    const screen = await mount({ context })
    try {
        await screen.open()
        const list = all(screen.body).find((node) => node.type === 'ol')
        assert.ok(hasClass(list, 'list-decimal'))
        const items = all(list).filter((node) => node.type === 'li')
        assert.deepEqual(items.map(textOf), ['완료일을 입력하면 · 저장한다 · 목록에 표시한다', '지난 날짜는 안내한다', '3 · 예 · 아니요'])
        assert.deepEqual(
            items.map((node) => node.props.value),
            [1, 2, 3]
        )
        assert.doesNotMatch(textOf(list), /given|then|actions|settings|\{|\}|\[object Object\]/)
        assert.equal(JSON.stringify(context), before)
    } finally {
        screen.cleanup()
    }
})

test('요청과 질문의 HTML 모양 문자열을 실행 요소 없이 글자로 표시한다', async () => {
    const unsafe = '<img src=x onerror="alert(1)"><script>alert(2)</script>'
    const screen = await mount({ context: { title: unsafe, originalContent: unsafe, latestFollowUpQuestion: unsafe, acceptanceCriteria: [{ then: unsafe }] } })
    try {
        await screen.open()
        assert.ok(findText(screen.root, 'button', unsafe))
        assert.ok(findText(screen.body, 'li', unsafe))
        assert.ok(findText(screen.body, 'p', unsafe))
        const nodes = [...all(screen.root), ...all(screen.body)]
        assert.equal(nodes.filter((node) => node.type === 'img' || node.type === 'script').length, 0)
        assert.equal(nodes.filter((node) => 'innerHTML' in node.props).length, 0)
    } finally {
        screen.cleanup()
    }
})

test('같은 버전의 상태 갱신은 열린 모달을 유지하고 버전이나 작업이 바뀌면 닫는다', async () => {
    const context = { versionId: 'version-3', title: '목표 완료일 추가', originalContent: '원문' }
    const screen = await mount({ context })
    try {
        await screen.open()
        await screen.update({ context: structuredClone(context) })
        assert.equal(screen.dialog().open, true)
        assert.equal(screen.body.style.overflow, 'hidden')
        await screen.update({ context: { ...context, versionId: 'version-4', title: '다른 버전 요청' } })
        assert.equal(screen.dialog().open, false)
        assert.equal(screen.body.style.overflow, 'auto')
        await screen.open()
        assert.equal(screen.dialog().open, true)
        await screen.update({ componentKey: 'job-4' })
        assert.equal(screen.dialog().open, false)
        assert.equal(screen.body.style.overflow, 'auto')
    } finally {
        screen.cleanup()
    }
})
