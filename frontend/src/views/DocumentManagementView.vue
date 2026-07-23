<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { getDocuments } from '@/api/appApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BasePagination from '@/components/base/BasePagination.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { useClientTable } from '@/composables/useClientTable'
import { useAuthStore } from '@/stores/authStore'

const authStore = useAuthStore()
const documents = ref([])
const keyword = ref('')
const loading = ref(false)

const columns = [
    { key: 'name', label: '문서명', minWidth: '330px', sortable: true },
    { key: 'project', label: '프로젝트', minWidth: '240px', sortable: true },
    { key: 'type', label: '분류', align: 'center', width: '130px' },
    { key: 'size', label: '크기', align: 'center', width: '100px' },
    { key: 'indexed', label: 'RAG 인덱스', align: 'center', width: '140px' },
    { key: 'updatedAt', label: '최근 변경', align: 'center', width: '170px', sortable: true },
]

const filteredDocuments = computed(() => {
    const value = keyword.value.trim().toLowerCase()

    if (!value) return documents.value

    return documents.value.filter((document) => {
        return [document.name, document.project, document.type]
            .some((item) => String(item).toLowerCase().includes(value))
    })
})

const {
    page,
    pageSize,
    sortKey,
    sortDirection,
    totalCount,
    pagedRows,
    handleSort,
    resetPage,
} = useClientTable({
    rows: filteredDocuments,
    defaultPageSize: 10,
    defaultSortKey: 'updatedAt',
    defaultSortDirection: 'desc',
})

const fetchDocuments = async () => {
    loading.value = true

    try {
        documents.value = await getDocuments()
    } finally {
        loading.value = false
    }
}

watch(keyword, resetPage)
onMounted(fetchDocuments)
</script>

<template>
    <div>
        <ViewHeading title="문서 관리" description="요구사항, 인터페이스 정의서, 업무절차 등 RAG 분석 문서를 관리합니다.">
            <template #actions>
                <BaseButton v-if="authStore.hasPermission('DOCUMENT_UPLOAD')">
                    + 문서 업로드
                </BaseButton>
            </template>
        </ViewHeading>

        <BaseInput
            v-model="keyword"
            class="mb-4 max-w-md"
            label="문서 검색"
            placeholder="문서명, 프로젝트, 분류 검색"
        />

        <BaseTable
            :columns="columns"
            :rows="pagedRows"
            :loading="loading"
            :sort-key="sortKey"
            :sort-direction="sortDirection"
            @sort-change="handleSort"
        >
            <template #cell-name="{ row }">
                <div class="font-extrabold text-slate-900 dark:text-slate-100">{{ row.name }}</div>
                <div class="mt-1 text-xs text-slate-400">{{ row.id }}</div>
            </template>

            <template #cell-type="{ value }">
                <span class="rounded-md bg-blue-50 px-2 py-1 text-xs font-bold text-blue-700 dark:bg-blue-950/50 dark:text-blue-300">
                    {{ value }}
                </span>
            </template>

            <template #cell-indexed="{ value }">
                <span v-if="value" class="font-bold text-emerald-600 dark:text-emerald-400">인덱싱 완료</span>
                <span v-else class="font-bold text-amber-600 dark:text-amber-400">대기</span>
            </template>

            <template #footer>
                <BasePagination
                    v-model:page="page"
                    :page-size="pageSize"
                    :total-count="totalCount"
                />
            </template>
        </BaseTable>
    </div>
</template>
