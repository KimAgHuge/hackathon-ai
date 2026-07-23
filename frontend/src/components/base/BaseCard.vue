<script setup>
defineProps({
    title: {
        type: String,
        default: '',
    },
    description: {
        type: String,
        default: '',
    },
    padding: {
        type: Boolean,
        default: true,
    },
})
</script>

<template>
    <section class="ui-panel">
        <header
            v-if="title || description || $slots.header || $slots.actions"
            class="flex items-start justify-between gap-4 border-b border-slate-100 px-6 py-5 dark:border-slate-800"
        >
            <slot name="header">
                <div>
                    <h2 v-if="title" class="text-lg font-extrabold text-slate-900 dark:text-slate-100">
                        {{ title }}
                    </h2>
                    <p v-if="description" class="mt-1 text-xs text-slate-500 dark:text-slate-400">
                        {{ description }}
                    </p>
                </div>
            </slot>

            <slot name="actions" />
        </header>

        <div :class="padding ? 'p-6' : ''">
            <slot />
        </div>

        <footer
            v-if="$slots.footer"
            class="border-t border-slate-100 px-6 py-4 dark:border-slate-800"
        >
            <slot name="footer" />
        </footer>
    </section>
</template>
