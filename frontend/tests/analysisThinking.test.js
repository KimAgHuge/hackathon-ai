import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, ref } from 'vue'
import { analysisThinkingError, analysisThinkingLabel, analysisThinkingEffortLabel } from '../src/lib/analysisThinking.js'

const moduleUrl = (source) => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`

test('현재 선택과 과거에 기록하지 않은 추론 설정을 구분한다', () => {
    assert.equal(analysisThinkingLabel(true), '켜짐')
    assert.equal(analysisThinkingLabel(false), '꺼짐')
    assert.equal(analysisThinkingLabel(null), '모델 기본값')
    assert.equal(analysisThinkingLabel(undefined), '모델 기본값')
    assert.equal(analysisThinkingEffortLabel('low', true), '낮음')
    assert.equal(analysisThinkingEffortLabel('medium', true), '보통')
    assert.equal(analysisThinkingEffortLabel('high', true), '높음')
    assert.equal(analysisThinkingEffortLabel('high', false), '—')
    assert.equal(analysisThinkingEffortLabel(null, true), '모델 기본값')
    assert.equal(analysisThinkingEffortLabel(undefined, null), '모델 기본값')
    assert.equal(analysisThinkingEffortLabel('unknown', true), '알 수 없음')
    assert.match(analysisThinkingError({ response: { data: { code: 'THINKING_NOT_SUPPORTED' } } }), /추론 모드를 끄거나/)
    assert.match(analysisThinkingError('THINKING_NOT_SUPPORTED: 모델 응답 오류'), /지원하지 않습니다/)
    assert.equal(analysisThinkingError(new Error('연결 실패')), null)
})

test('FastAPI 오류의 중첩된 상세 코드와 문자열 상세를 모두 안내한다', () => {
    for (const detail of [
        { code: 'THINKING_NOT_SUPPORTED', message: '모델 응답 오류' },
        { message: 'THINKING_NOT_SUPPORTED: 모델 응답 오류' },
        'THINKING_NOT_SUPPORTED: 모델 응답 오류',
    ]) {
        assert.match(analysisThinkingError({ response: { data: { detail } } }), /추론 모드를 끄거나/)
    }
})

test('분석 시작 통신에서 기본 보통과 선택한 강도를 보내고 저장 근거를 보존한다', async () => {
    const calls = []
    const key = '__analysisThinkingApiTest'
    globalThis[key] = { post: async (...args) => (calls.push(args), { data: { jobId: 'job-test' } }) }
    try {
        const source = (await readFile(new URL('../src/api/analysisApi.js', import.meta.url), 'utf8'))
            .replace("import { httpClient } from '@/api/httpClient'", `const httpClient = globalThis.${key}`)
            .replace("'@/lib/requirementWorkflow'", JSON.stringify(new URL('../src/lib/requirementWorkflow.js', import.meta.url).href))
            .replace('import.meta.env.VITE_USE_MOCK', "'false'")
        const { startProjectAnalysis } = await import(moduleUrl(source))
        await startProjectAnalysis('project-test')
        await startProjectAnalysis('project-test', 'version-test', 'snapshot-test', false, 'high')
        await startProjectAnalysis('project-test', 'version-test', null, true, 'low')
        await assert.rejects(startProjectAnalysis('project-test', null, null, true, 'invalid'), /추론 강도/)
        assert.deepEqual(calls, [
            ['/projects/project-test/analysis-jobs', { thinkingEnabled: true, thinkingEffort: 'medium' }],
            [
                '/projects/project-test/analysis-jobs',
                {
                    thinkingEnabled: false,
                    thinkingEffort: 'high',
                    requirementVersionId: 'version-test',
                    reuseFromJobId: 'snapshot-test',
                    confirmedUnchanged: true,
                },
            ],
            ['/projects/project-test/analysis-jobs', { thinkingEnabled: true, thinkingEffort: 'low', requirementVersionId: 'version-test' }],
        ])
    } finally {
        delete globalThis[key]
    }
})

test('추론 강도는 기본 보통이며 선택값을 보존하고 해제하거나 처리 중일 때 비활성화한다', async () => {
    const source = await readFile(new URL('../src/components/analysis/AnalysisThinkingControl.vue', import.meta.url), 'utf8')
    const compiled = compileScript(parse(source).descriptor, { id: 'thinking-control-test', inlineTemplate: true }).content
    const { default: Control } = await import(moduleUrl(compiled.replace(/from ['"]vue['"]/g, `from ${JSON.stringify(import.meta.resolve('vue'))}`)))
    const makeNode = (type, text = '') => ({ type, text, children: [], props: {}, parent: null })
    const renderer = createRenderer({
        createElement: makeNode,
        createText: (text) => makeNode('#text', text),
        createComment: (text) => makeNode('#comment', text),
        setText: (node, text) => (node.text = text),
        setElementText: (node, text) => (node.text = text),
        parentNode: (node) => node.parent,
        nextSibling: () => null,
        patchProp: (node, key, _, value) => (node.props[key] = value),
        insert: (node, parent) => {
            node.parent = parent
            parent.children.push(node)
        },
        remove: (node) => node.parent?.children.splice(node.parent.children.indexOf(node), 1),
    })
    const root = makeNode('root')
    const enabled = ref(true)
    const effort = ref('medium')
    const disabled = ref(false)
    const app = renderer.createApp({
        setup: () => () =>
            h(Control, {
                modelValue: enabled.value,
                thinkingEffort: effort.value,
                disabled: disabled.value,
                'onUpdate:modelValue': (value) => (enabled.value = value),
                'onUpdate:thinkingEffort': (value) => (effort.value = value),
            }),
    })
    app.mount(root)
    const descendants = (node) => [node, ...node.children.flatMap(descendants)]
    const input = descendants(root).find((node) => node.type === 'input')
    const select = descendants(root).find((node) => node.type === 'select')
    assert.equal(select.props.value, 'medium')
    assert.equal(select.props.disabled, false)
    assert.deepEqual(
        select.children.filter((node) => node.type === 'option').map((node) => node.props.value),
        ['low', 'medium', 'high']
    )
    select.props.onChange({ target: { value: 'high' } })
    await nextTick()
    assert.equal(effort.value, 'high')
    assert.equal(select.props.value, 'high')
    assert.equal(input.props.checked, true)
    input.props.onChange({ target: { checked: false } })
    await nextTick()
    assert.equal(enabled.value, false)
    assert.equal(input.props.checked, false)
    assert.equal(select.props.disabled, true)
    assert.equal(select.props.value, 'high')
    input.props.onChange({ target: { checked: true } })
    await nextTick()
    assert.equal(select.props.disabled, false)
    assert.equal(select.props.value, 'high')
    disabled.value = true
    await nextTick()
    assert.equal(input.props.disabled, true)
    assert.equal(select.props.disabled, true)
    app.unmount()
})
