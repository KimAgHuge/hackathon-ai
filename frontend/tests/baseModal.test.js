import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { compileScript, parse } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, ref } from 'vue'

const source = await readFile(new URL('../src/components/base/BaseModal.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'modal-test', inlineTemplate: true })
const code = compiled.content.replace(/from (['"])vue\1/g, `from ${JSON.stringify(import.meta.resolve('vue'))}`)
const { default: BaseModal } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)

// 실제 Vue 갱신을 사용하고 브라우저 대화상자·스크롤 API만 시험용으로 대체한다.
const makeNode = (type, text = '') => ({
    type,
    text,
    children: [],
    props: {},
    style: { overflow: '' },
    parent: null,
    open: false,
    showModal() {
        this.open = true
    },
    close() {
        this.open = false
        this.props.onClose?.()
    },
})

const mountModal = async () => {
    const body = makeNode('body')
    body.style.overflow = 'auto'
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
        setElementText: (node, text) => (node.text = text),
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
    const open = ref(false)
    const busy = ref(false)
    const app = renderer.createApp({
        setup: () => () =>
            h(BaseModal, { modelValue: open.value, title: '검토 상세', busy: busy.value, 'onUpdate:modelValue': (value) => (open.value = value) }, () =>
                h('textarea', { value: '편집 중인 초안' })
            ),
    })
    app.mount(makeNode('root'))
    const settle = async () => {
        await nextTick()
        await nextTick()
    }
    await settle()
    const dialog = body.children.find((node) => node.type === 'dialog')
    const cleanup = () => {
        app.unmount()
        delete globalThis.document
    }
    return { open, busy, body, dialog, settle, cleanup }
}

test('모달을 열면 배경 스크롤을 잠그고 닫으면 복원한다', async () => {
    const modal = await mountModal()
    try {
        assert.equal(modal.dialog.open, false)
        modal.open.value = true
        await modal.settle()
        assert.equal(modal.dialog.open, true)
        assert.equal(modal.body.style.overflow, 'hidden')
        modal.open.value = false
        await modal.settle()
        assert.equal(modal.dialog.open, false)
        assert.equal(modal.body.style.overflow, 'auto')
    } finally {
        modal.cleanup()
    }
})

test('처리 중에는 Escape로 닫히지 않고 완료 후에는 닫을 수 있다', async () => {
    const modal = await mountModal()
    const event = { preventDefault() {} }
    try {
        modal.open.value = true
        modal.busy.value = true
        await modal.settle()
        modal.dialog.props.onCancel(event)
        await modal.settle()
        assert.equal(modal.open.value, true)
        modal.busy.value = false
        await modal.settle()
        modal.dialog.props.onCancel(event)
        await modal.settle()
        assert.equal(modal.open.value, false)
        assert.equal(modal.body.style.overflow, 'auto')
    } finally {
        modal.cleanup()
    }
})

test('모달을 다시 열어도 편집 영역을 새로 만들지 않는다', async () => {
    const modal = await mountModal()
    try {
        modal.open.value = true
        await modal.settle()
        const content = modal.dialog.children.find((node) => node.props.class === 'base-modal-body')
        const editor = content.children.find((node) => node.type === 'textarea')
        modal.open.value = false
        await modal.settle()
        modal.open.value = true
        await modal.settle()
        assert.equal(
            content.children.find((node) => node.type === 'textarea'),
            editor
        )
        assert.equal(editor.props.value, '편집 중인 초안')
    } finally {
        modal.cleanup()
    }
})

test('열린 모달이 화면에서 제거되어도 배경 스크롤을 복원한다', async () => {
    const modal = await mountModal()
    modal.open.value = true
    await modal.settle()
    modal.cleanup()
    assert.equal(modal.body.style.overflow, 'auto')
})
