# doenaai 공통 컴포넌트 사용 가이드

이 문서는 `doenaai-frontend`에 포함된 공통 UI 컴포넌트와 테이블 컴포넌트를 화면에서 재사용하는 방법을 설명합니다.

## 1. 기본 원칙

공통 컴포넌트는 다음 기준으로 사용합니다.

- 화면마다 반복되는 구조와 동작은 Vue 컴포넌트로 분리합니다.
- 색상, 간격, 테두리, 입력창 모양처럼 반복되는 디자인은 Tailwind CSS와 공통 CSS 클래스로 관리합니다.
- 단순한 `div` 배치 한 번만을 위해 컴포넌트를 만들지는 않습니다.
- 공통 컴포넌트 원본을 화면별로 복사하지 않습니다.
- 화면마다 달라지는 부분은 `props`, `slot`, 이벤트로 전달합니다.
- 업무 API 호출과 화면별 상태 관리는 View 또는 Store에 둡니다.

권장 역할 분리는 다음과 같습니다.

```text
Base 컴포넌트
├─ 공통 구조
├─ 공통 디자인
├─ 공통 상호작용
└─ props와 event 정의

View 컴포넌트
├─ API 호출
├─ 검색 조건
├─ 컬럼 정의
├─ 화면별 셀 표현
└─ 상세·수정·삭제 처리
```

## 2. 컴포넌트 위치

```text
src/components/
├─ base/
│  ├─ BaseButton.vue
│  ├─ BaseCard.vue
│  ├─ BaseInput.vue
│  ├─ BasePagination.vue
│  ├─ BaseSelect.vue
│  ├─ BaseTable.vue
│  └─ BaseTextarea.vue
│
├─ common/
│  ├─ PageTitle.vue
│  ├─ StatusBadge.vue
│  ├─ StepHeader.vue
│  └─ ViewHeading.vue
│
└─ layout/
   ├─ AppHeader.vue
   └─ AppSidebar.vue
```

컴포넌트는 `@` alias를 사용해 가져옵니다.

```js
import BaseButton from '@/components/base/BaseButton.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
```

`@`는 `src` 폴더를 가리킵니다.

---

# 3. BaseButton

버튼, 내부 페이지 링크, 외부 링크를 같은 디자인으로 사용할 수 있습니다.

## 기본 사용

```vue
<script setup>
import BaseButton from '@/components/base/BaseButton.vue'
</script>

<template>
    <BaseButton @click="saveProject">
        저장
    </BaseButton>
</template>
```

## variant

```vue
<BaseButton variant="primary">저장</BaseButton>
<BaseButton variant="secondary">취소</BaseButton>
<BaseButton variant="danger">삭제</BaseButton>
<BaseButton variant="ghost">상세</BaseButton>
```

지원 값:

| 값 | 용도 |
|---|---|
| `primary` | 주요 실행 버튼 |
| `secondary` | 보조 버튼 |
| `danger` | 삭제, 중지 등 위험 작업 |
| `ghost` | 표 안의 상세, 수정 등 가벼운 작업 |

## size

```vue
<BaseButton size="small">작게</BaseButton>
<BaseButton size="medium">기본</BaseButton>
<BaseButton size="large">크게</BaseButton>
```

지원 값은 `small`, `medium`, `large`입니다.

## 로딩과 비활성화

```vue
<BaseButton
    :loading="saving"
    :disabled="!formValid"
    @click="saveProject"
>
    저장
</BaseButton>
```

`loading`이 `true`이면 스피너가 표시되고 버튼 입력이 차단됩니다.

## 전체 너비

```vue
<BaseButton block>
    로그인
</BaseButton>
```

## RouterLink로 사용

```vue
<BaseButton :to="{ name: 'project-register' }">
    프로젝트 등록
</BaseButton>
```

## 외부 링크로 사용

```vue
<BaseButton
    href="https://example.com"
    target="_blank"
    rel="noopener noreferrer"
>
    외부 문서
</BaseButton>
```

## Props

| Prop | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `variant` | String | `primary` | 버튼 종류 |
| `size` | String | `medium` | 버튼 크기 |
| `type` | String | `button` | HTML 버튼 타입 |
| `loading` | Boolean | `false` | 로딩 표시 및 입력 차단 |
| `disabled` | Boolean | `false` | 비활성화 |
| `block` | Boolean | `false` | 전체 너비 |
| `to` | String, Object | `null` | Vue Router 이동 대상 |
| `href` | String | 빈 문자열 | 외부 링크 주소 |

---

# 4. BaseInput

라벨, 필수 표시, 안내 문구, 오류 문구가 포함된 공통 입력 필드입니다.

## 기본 사용

```vue
<script setup>
import { reactive } from 'vue'
import BaseInput from '@/components/base/BaseInput.vue'

const form = reactive({
    projectName: '',
})
</script>

<template>
    <BaseInput
        v-model="form.projectName"
        name="projectName"
        label="프로젝트명"
        placeholder="프로젝트명을 입력하세요"
        required
    />
</template>
```

`type`, `placeholder`, `autocomplete`, `disabled`, `maxlength` 같은 일반 input 속성은 그대로 전달할 수 있습니다.

```vue
<BaseInput
    v-model="form.email"
    name="email"
    label="이메일"
    type="email"
    autocomplete="email"
    placeholder="admin@doenaai.ai"
/>
```

## 안내 문구

```vue
<BaseInput
    v-model="form.repositoryUrl"
    label="저장소 URL"
    hint="HTTPS Git 저장소 주소를 입력하세요."
/>
```

## 오류 표시

```vue
<BaseInput
    v-model="form.projectName"
    label="프로젝트명"
    :error="errors.projectName"
/>
```

```js
const errors = reactive({
    projectName: '프로젝트명은 필수입니다.',
})
```

`error`가 있으면 `hint`보다 오류 문구가 우선 표시됩니다.

## Props

| Prop | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `modelValue` | String, Number | 빈 문자열 | `v-model` 값 |
| `label` | String | 빈 문자열 | 라벨 |
| `id` | String | 빈 문자열 | input ID |
| `name` | String | 빈 문자열 | input name이며 ID 대체값으로도 사용 |
| `hint` | String | 빈 문자열 | 안내 문구 |
| `error` | String | 빈 문자열 | 오류 문구 |
| `required` | Boolean | `false` | 필수 표시 |

---

# 5. BaseSelect

배열로 옵션을 전달하는 공통 선택 컴포넌트입니다.

## 문자열 옵션

```vue
<script setup>
import { ref } from 'vue'
import BaseSelect from '@/components/base/BaseSelect.vue'

const status = ref('ACTIVE')
const statusOptions = ['ACTIVE', 'INACTIVE', 'ARCHIVED']
</script>

<template>
    <BaseSelect
        v-model="status"
        label="상태"
        :options="statusOptions"
    />
</template>
```

## 객체 옵션

기본적으로 `label`, `value` 필드를 사용합니다.

```js
const statusOptions = [
    { label: '전체', value: '' },
    { label: '활성', value: 'ACTIVE' },
    { label: '비활성', value: 'INACTIVE' },
]
```

```vue
<BaseSelect
    v-model="search.status"
    label="상태"
    :options="statusOptions"
/>
```

## 필드명이 다른 옵션

```js
const roleOptions = [
    { name: '관리자', code: 'ADMIN' },
    { name: '분석 담당자', code: 'ANALYST' },
]
```

```vue
<BaseSelect
    v-model="form.role"
    label="역할"
    :options="roleOptions"
    option-label="name"
    option-value="code"
/>
```

## 첫 번째 안내 옵션 추가

```vue
<BaseSelect
    v-model="form.role"
    label="역할"
    :options="roleOptions"
>
    <template #before-options>
        <option value="" disabled>
            역할을 선택하세요
        </option>
    </template>
</BaseSelect>
```

## 숫자 값 사용

초기 `v-model` 값이 숫자이면 선택 결과를 숫자로 변환합니다.

```js
const pageSize = ref(20)
const pageSizeOptions = [10, 20, 50, 100]
```

```vue
<BaseSelect
    v-model="pageSize"
    :options="pageSizeOptions"
/>
```

## Props

| Prop | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `modelValue` | String, Number, Boolean | 빈 문자열 | `v-model` 값 |
| `options` | Array | 빈 배열 | 옵션 목록 |
| `label` | String | 빈 문자열 | 라벨 |
| `optionLabel` | String | `label` | 표시 필드명 |
| `optionValue` | String | `value` | 값 필드명 |
| `hint` | String | 빈 문자열 | 안내 문구 |
| `error` | String | 빈 문자열 | 오류 문구 |
| `required` | Boolean | `false` | 필수 표시 |

---

# 6. BaseTextarea

여러 줄 입력을 위한 공통 컴포넌트입니다.

```vue
<script setup>
import BaseTextarea from '@/components/base/BaseTextarea.vue'
</script>

<template>
    <BaseTextarea
        v-model="form.description"
        name="description"
        label="변경요청 내용"
        placeholder="변경하려는 내용을 입력하세요"
        rows="8"
        required
    />
</template>
```

`rows`, `maxlength`, `disabled`, `placeholder` 같은 textarea 속성은 그대로 전달할 수 있습니다.

Props는 `BaseInput`과 동일한 구조이며 `modelValue`는 문자열입니다.

---

# 7. BaseCard

제목, 설명, 상단 액션, 본문, 하단 액션을 제공하는 공통 패널입니다.

## 기본 사용

```vue
<script setup>
import BaseCard from '@/components/base/BaseCard.vue'
</script>

<template>
    <BaseCard
        title="AI 추론 서버"
        description="Mac Studio의 FastAPI 서버 연결을 관리합니다."
    >
        카드 본문
    </BaseCard>
</template>
```

## actions 슬롯

```vue
<BaseCard title="AI 추론 서버">
    <template #actions>
        <StatusBadge status="ONLINE" />
    </template>

    카드 본문
</BaseCard>
```

## footer 슬롯

```vue
<BaseCard title="프로젝트 설정">
    설정 내용

    <template #footer>
        <div class="flex justify-end gap-2">
            <BaseButton variant="secondary">
                취소
            </BaseButton>

            <BaseButton>
                저장
            </BaseButton>
        </div>
    </template>
</BaseCard>
```

## header 전체 변경

```vue
<BaseCard>
    <template #header>
        <div class="flex items-center gap-3">
            <div class="font-bold">사용자 정의 헤더</div>
            <StatusBadge status="ACTIVE" />
        </div>
    </template>

    본문
</BaseCard>
```

## 내부 padding 제거

테이블이나 자체 패딩이 있는 요소를 카드 내부에 넣을 때 사용합니다.

```vue
<BaseCard title="최근 분석" :padding="false">
    <BaseTable
        :columns="columns"
        :rows="rows"
    />
</BaseCard>
```

## Props

| Prop | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `title` | String | 빈 문자열 | 카드 제목 |
| `description` | String | 빈 문자열 | 카드 설명 |
| `padding` | Boolean | `true` | 본문 패딩 사용 여부 |

## Slots

| Slot | 설명 |
|---|---|
| `default` | 본문 |
| `header` | 헤더 전체 교체 |
| `actions` | 헤더 오른쪽 액션 |
| `footer` | 하단 영역 |

---

# 8. ViewHeading

관리자 화면의 상단 제목과 설명을 통일합니다.

```vue
<script setup>
import ViewHeading from '@/components/common/ViewHeading.vue'
import BaseButton from '@/components/base/BaseButton.vue'
</script>

<template>
    <ViewHeading
        title="프로젝트 관리"
        description="분석 대상 프로젝트의 소스코드와 DB 연결 정보를 관리합니다."
    >
        <template #actions>
            <BaseButton :to="{ name: 'project-register' }">
                프로젝트 등록
            </BaseButton>
        </template>
    </ViewHeading>
</template>
```

---

# 9. StatusBadge

상태 코드를 공통 디자인과 한글 라벨로 표시합니다.

```vue
<StatusBadge status="ACTIVE" />
<StatusBadge status="ANALYZING" />
<StatusBadge status="ERROR" />
```

현재 기본 지원 상태:

```text
ONLINE
ACTIVE
READY
COMPLETED
APPROVED
ANALYZED
REVIEW
PENDING
REQUESTED
ANALYZING
STANDBY
CORRECTED
OFFLINE
ARCHIVED
ERROR
```

새로운 상태를 추가하려면 `src/components/common/StatusBadge.vue`의 `styles`, `labels`에 같은 키를 추가합니다.

```js
const styles = {
    // 기존 항목
    BLOCKED: 'border-rose-200 bg-rose-50 text-rose-700',
}

const labels = {
    // 기존 항목
    BLOCKED: '차단',
}
```

---

# 10. BaseTable

`BaseTable`은 데이터 배열의 크기에 따라 행을 자동 생성합니다.

```text
rows 0건   → 빈 데이터 표시
rows 3건   → 3개 행 생성
rows 20건  → 20개 행 생성
rows 변경  → 화면 자동 갱신
```

컬럼은 화면에서 명시적으로 정의하고, 행은 API 결과를 전달합니다.

## 가장 단순한 사용

```vue
<script setup>
import { ref } from 'vue'
import BaseTable from '@/components/base/BaseTable.vue'

const columns = [
    { key: 'code', label: '프로젝트 코드' },
    { key: 'name', label: '프로젝트명' },
    { key: 'status', label: '상태' },
]

const rows = ref([
    {
        id: 'PRJ-001',
        code: 'PRJ-001',
        name: '공공 마이데이터 연계',
        status: 'ACTIVE',
    },
])
</script>

<template>
    <BaseTable
        :columns="columns"
        :rows="rows"
    />
</template>
```

## API 데이터 연결

```vue
<script setup>
import { onMounted, ref } from 'vue'
import BaseTable from '@/components/base/BaseTable.vue'
import { projectApi } from '@/api/projectApi'

const rows = ref([])
const loading = ref(false)

const columns = [
    { key: 'code', label: '프로젝트 코드' },
    { key: 'name', label: '프로젝트명' },
    { key: 'status', label: '상태' },
]

const fetchProjects = async () => {
    loading.value = true

    try {
        const { data } = await projectApi.getProjects()
        rows.value = data.content
    } finally {
        loading.value = false
    }
}

onMounted(fetchProjects)
</script>

<template>
    <BaseTable
        :columns="columns"
        :rows="rows"
        :loading="loading"
    />
</template>
```

API 응답이 바뀌면 `rows.value`만 교체하면 됩니다.

```js
rows.value = response.data.content
```

## 컬럼 정의

```js
const columns = [
    {
        key: 'code',
        label: '프로젝트 코드',
        width: '140px',
    },
    {
        key: 'name',
        label: '프로젝트명',
        minWidth: '240px',
    },
    {
        key: 'status',
        label: '상태',
        width: '100px',
        align: 'center',
    },
    {
        key: 'updatedAt',
        label: '최근 수정일',
        width: '170px',
        align: 'center',
        sortable: true,
    },
]
```

### 컬럼 옵션

| 속성 | 타입 | 설명 |
|---|---|---|
| `key` | String | 행 객체의 필드명이며 슬롯 이름에도 사용 |
| `label` | String | 헤더 표시명 |
| `width` | String | 고정 너비 |
| `minWidth` | String | 최소 너비 |
| `maxWidth` | String | 최대 너비 |
| `align` | String | `left`, `center`, `right` |
| `sortable` | Boolean | 정렬 버튼 표시 |
| `visible` | Boolean | `false`이면 컬럼 숨김 |
| `value` | Function | 셀 값을 가공하는 함수 |
| `headerClass` | String | 헤더에 추가할 Tailwind 클래스 |
| `cellClass` | String | 셀에 추가할 Tailwind 클래스 |

## 값 가공

단순 문자열 가공은 `value` 함수를 사용합니다.

```js
const columns = [
    {
        key: 'createdBy',
        label: '등록자',
        value: (row) => `${row.createdByName} / ${row.department}`,
    },
    {
        key: 'updatedAt',
        label: '수정일',
        value: (row) => formatDateTime(row.updatedAt),
    },
]
```

## 특정 셀 변경

복잡한 셀은 `cell-컬럼키` 슬롯을 사용합니다.

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
>
    <template #cell-name="{ row }">
        <div>
            <div class="font-bold text-slate-900 dark:text-slate-100">
                {{ row.name }}
            </div>

            <div class="mt-1 text-xs text-slate-400">
                {{ row.description }}
            </div>
        </div>
    </template>

    <template #cell-status="{ value }">
        <StatusBadge :status="value" />
    </template>
</BaseTable>
```

슬롯에서 제공되는 값:

| 값 | 설명 |
|---|---|
| `row` | 현재 행 전체 객체 |
| `column` | 현재 컬럼 설정 객체 |
| `value` | 기본값 또는 `column.value()` 처리 결과 |

## 관리 버튼 컬럼

```js
const columns = [
    { key: 'name', label: '프로젝트명' },
    {
        key: 'actions',
        label: '관리',
        width: '140px',
        align: 'center',
    },
]
```

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
>
    <template #cell-actions="{ row }">
        <div class="flex justify-center gap-2">
            <BaseButton
                size="small"
                variant="ghost"
                @click="openDetail(row)"
            >
                상세
            </BaseButton>

            <BaseButton
                size="small"
                variant="secondary"
                @click="editProject(row)"
            >
                수정
            </BaseButton>
        </div>
    </template>
</BaseTable>
```

버튼 클릭은 행 클릭 이벤트와 분리됩니다. `BaseTable`은 버튼, 링크, input 등에서 발생한 클릭을 행 클릭으로 처리하지 않습니다.

## 행 클릭

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
    clickable
    @row-click="openDetail"
/>
```

```js
const openDetail = (row) => {
    router.push({
        name: 'project-detail',
        params: {
            projectId: row.id,
        },
    })
}
```

## 체크박스 선택

```vue
<script setup>
import { ref } from 'vue'

const selectedIds = ref([])
</script>

<template>
    <BaseTable
        v-model:selected-keys="selectedIds"
        :columns="columns"
        :rows="rows"
        selectable
    />

    <div class="mt-3 text-sm">
        {{ selectedIds.length }}개 선택됨
    </div>
</template>
```

`selectedKeys`에는 행 객체가 아니라 `rowKey` 값이 저장됩니다.

## rowKey 변경

기본 행 키 필드는 `id`입니다.

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
    row-key="projectId"
/>
```

복합 키도 함수로 만들 수 있습니다.

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
    :row-key="(row) => `${row.projectId}-${row.version}`"
/>
```

## 행별 스타일

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
    :row-class="getRowClass"
/>
```

```js
const getRowClass = (row) => {
    if (row.riskLevel === 'CRITICAL') {
        return 'bg-rose-50 dark:bg-rose-950/20'
    }

    if (row.status === 'ARCHIVED') {
        return 'opacity-60'
    }

    return ''
}
```

모든 행에 동일한 클래스를 적용할 때는 문자열을 전달합니다.

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
    row-class="hover:bg-blue-50"
/>
```

## 컬럼 조건부 표시

```js
import { computed } from 'vue'
import { useAuthStore } from '@/stores/authStore'

const authStore = useAuthStore()

const columns = computed(() => [
    {
        key: 'name',
        label: '프로젝트명',
    },
    {
        key: 'createdBy',
        label: '등록자',
        visible: authStore.hasPermission('USER_MANAGE'),
    },
    {
        key: 'actions',
        label: '관리',
        visible: authStore.hasPermission('PROJECT_UPDATE'),
    },
])
```

## 정렬

컬럼에 `sortable: true`를 설정하고 현재 정렬 상태를 전달합니다.

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
    :sort-key="sortKey"
    :sort-direction="sortDirection"
    @sort-change="handleSort"
/>
```

이벤트 값:

```js
{
    key: 'updatedAt',
    direction: 'desc',
}
```

서버 정렬에서는 이벤트를 받아 API를 다시 호출합니다.

```js
const handleSort = ({ key, direction }) => {
    search.sortKey = key
    search.sortDirection = direction
    search.page = 0
    fetchProjects()
}
```

## 높이 제한과 고정 헤더

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
    max-height="560px"
    sticky-header
/>
```

`max-height`를 설정하지 않으면 행 개수에 따라 자연스럽게 높이가 늘어납니다.

## 빈 데이터 변경

문구만 변경:

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
    empty-text="등록된 프로젝트가 없습니다."
/>
```

빈 화면 전체 변경:

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
>
    <template #empty>
        <div class="py-4">
            <div class="font-bold text-slate-700 dark:text-slate-200">
                등록된 문서가 없습니다.
            </div>

            <div class="mt-2 text-xs text-slate-400">
                문서를 등록하면 AI 분석에 활용됩니다.
            </div>

            <BaseButton class="mt-4" @click="openUploadModal">
                문서 등록
            </BaseButton>
        </div>
    </template>
</BaseTable>
```

## 헤더 변경

`header-컬럼키` 슬롯을 사용합니다.

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
>
    <template #header-riskLevel>
        <div class="inline-flex items-center gap-1">
            위험도
            <span title="AI가 계산한 변경 위험도">ⓘ</span>
        </div>
    </template>
</BaseTable>
```

## Footer와 페이지네이션

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
>
    <template #footer>
        <BasePagination
            v-model:page="page"
            :page-size="pageSize"
            :total-count="totalCount"
            @change="fetchProjects"
        />
    </template>
</BaseTable>
```

## BaseTable Props

| Prop | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `columns` | Array | 필수 | 컬럼 설정 |
| `rows` | Array | 빈 배열 | 표시할 행 데이터 |
| `rowKey` | String, Function | `id` | 행 고유키 |
| `rowClass` | String, Function | 빈 문자열 | 행 추가 클래스 |
| `loading` | Boolean | `false` | 로딩 상태 |
| `emptyText` | String | 조회된 데이터가 없습니다. | 빈 데이터 문구 |
| `clickable` | Boolean | `false` | 행 클릭 활성화 |
| `selectable` | Boolean | `false` | 체크박스 활성화 |
| `selectedKeys` | Array | 빈 배열 | 선택된 행 키 |
| `maxHeight` | String | 빈 문자열 | 테이블 최대 높이 |
| `stickyHeader` | Boolean | `false` | 헤더 고정 |
| `sortKey` | String | 빈 문자열 | 현재 정렬 컬럼 |
| `sortDirection` | String | `asc` | `asc` 또는 `desc` |

## BaseTable Events

| Event | 값 | 설명 |
|---|---|---|
| `row-click` | row | 행 클릭 |
| `sort-change` | `{ key, direction }` | 정렬 변경 |
| `update:selectedKeys` | Array | 선택 키 변경 |

## BaseTable Slots

| Slot | 설명 |
|---|---|
| `cell-{key}` | 컬럼 셀 변경 |
| `header-{key}` | 컬럼 헤더 변경 |
| `empty` | 빈 데이터 화면 변경 |
| `footer` | 페이지네이션 또는 하단 액션 |

---

# 11. BasePagination

페이지 번호는 Spring Data 방식에 맞춰 `0`부터 시작합니다.

```vue
<script setup>
import { ref } from 'vue'
import BasePagination from '@/components/base/BasePagination.vue'

const page = ref(0)
const pageSize = ref(20)
const totalCount = ref(137)

const changePage = (nextPage) => {
    page.value = nextPage
    fetchProjects()
}
</script>

<template>
    <BasePagination
        v-model:page="page"
        :page-size="pageSize"
        :total-count="totalCount"
        @change="changePage"
    />
</template>
```

| Prop | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `page` | Number | `0` | 현재 페이지, 0부터 시작 |
| `pageSize` | Number | `20` | 페이지당 건수 |
| `totalCount` | Number | `0` | 전체 건수 |

| Event | 설명 |
|---|---|
| `update:page` | `v-model:page` 갱신 |
| `change` | 페이지 변경 후 추가 처리 |

---

# 12. useClientTable

Mock 데이터나 소량 데이터를 프론트에서 정렬하고 페이지 처리할 때 사용합니다.

대량 데이터와 운영 API에는 서버 페이지네이션을 권장합니다.

## 기본 사용

```vue
<script setup>
import { ref } from 'vue'
import BasePagination from '@/components/base/BasePagination.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import { useClientTable } from '@/composables/useClientTable'

const rows = ref([
    { id: 1, name: '프로젝트 A', updatedAt: '2026-07-23' },
    { id: 2, name: '프로젝트 B', updatedAt: '2026-07-22' },
])

const columns = [
    { key: 'name', label: '프로젝트명', sortable: true },
    { key: 'updatedAt', label: '수정일', sortable: true },
]

const {
    page,
    pageSize,
    sortKey,
    sortDirection,
    totalCount,
    pagedRows,
    handleSort,
} = useClientTable({
    rows,
    defaultPageSize: 5,
    defaultSortKey: 'updatedAt',
    defaultSortDirection: 'desc',
})
</script>

<template>
    <BaseTable
        :columns="columns"
        :rows="pagedRows"
        :sort-key="sortKey"
        :sort-direction="sortDirection"
        @sort-change="handleSort"
    >
        <template #footer>
            <BasePagination
                v-model:page="page"
                :page-size="pageSize"
                :total-count="totalCount"
            />
        </template>
    </BaseTable>
</template>
```

반환 값:

| 값 | 설명 |
|---|---|
| `page` | 현재 페이지 |
| `pageSize` | 페이지 크기 |
| `sortKey` | 정렬 키 |
| `sortDirection` | 정렬 방향 |
| `totalCount` | 전체 건수 |
| `pageCount` | 전체 페이지 수 |
| `pagedRows` | 현재 페이지 행 |
| `handleSort` | BaseTable 정렬 이벤트 처리 |
| `resetPage` | 페이지를 0으로 초기화 |

---

# 13. 로그인 화면에서 공통 컴포넌트 사용

`LoginView.vue`는 `BaseInput`, `BaseButton`을 사용합니다.

```vue
<form class="mt-9 space-y-5" @submit.prevent="handleLogin">
    <BaseInput
        v-model.trim="form.email"
        name="email"
        label="이메일"
        type="email"
        autocomplete="email"
        placeholder="admin@doenaai.ai"
        required
    />

    <BaseInput
        v-model="form.password"
        name="password"
        label="비밀번호"
        type="password"
        autocomplete="current-password"
        placeholder="비밀번호를 입력하세요"
        required
    />

    <BaseButton
        type="submit"
        size="large"
        block
        :loading="authStore.loading"
    >
        로그인
    </BaseButton>
</form>
```

로그인 처리 자체는 `authStore`가 담당하고 공통 컴포넌트는 입력과 디자인만 담당합니다.

```js
const handleLogin = async () => {
    await authStore.login(form.email, form.password)
    await router.replace('/dashboard')
}
```

---

# 14. 권한에 따른 공통 컴포넌트 표시

프론트의 권한 표시는 사용자 편의를 위한 UI 제어입니다. 최종 권한 검사는 반드시 Spring Boot에서도 수행해야 합니다.

```vue
<BaseButton
    v-if="authStore.hasPermission('PROJECT_CREATE')"
    :to="{ name: 'project-register' }"
>
    프로젝트 등록
</BaseButton>
```

테이블 관리 컬럼도 숨길 수 있습니다.

```js
const columns = computed(() => [
    { key: 'name', label: '프로젝트명' },
    {
        key: 'actions',
        label: '관리',
        visible: authStore.hasPermission('PROJECT_UPDATE'),
    },
])
```

---

# 15. Tailwind CSS와 공통 클래스

전역 스타일 진입점:

```text
src/assets/styles/main.css
```

현재 정의된 공통 클래스:

```text
ui-panel
ui-label
ui-input
ui-textarea
ui-button
ui-button-primary
ui-button-secondary
ui-button-danger
ui-button-ghost
ui-table-container
```

Base 컴포넌트 내부에서 이 클래스를 사용합니다.

```vue
<button class="ui-button ui-button-primary">
    저장
</button>
```

## 색상 변경

`@theme`의 브랜드 값을 변경하면 관련 Tailwind 클래스가 함께 변경됩니다.

```css
@theme {
    --color-brand-500: #3b82f6;
    --color-brand-600: #2563eb;
    --color-brand-700: #1d4ed8;
    --color-brand-800: #1e40af;
}
```

사용 예:

```vue
<div class="bg-brand-700 text-white">
    브랜드 영역
</div>
```

## 공통 디자인 변경

모든 입력창 디자인을 바꾸려면 `BaseInput.vue`를 화면마다 수정하지 않고 `.ui-input`만 변경합니다.

```css
@layer components {
    .ui-input {
        @apply h-11 rounded-xl border-slate-300;
        @apply focus:border-brand-500 focus:ring-3 focus:ring-brand-500/10;
    }
}
```

모든 공통 테이블 외곽 디자인 변경:

```css
@layer components {
    .ui-table-container {
        @apply overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm;
        @apply dark:border-slate-700 dark:bg-slate-900;
    }
}
```

## 화면 한 곳만 변경

공통 디자인을 바꾸지 않고 한 화면만 변경할 때는 컴포넌트에 추가 클래스를 전달합니다.

```vue
<BaseButton class="min-w-32">
    저장
</BaseButton>
```

```vue
<BaseInput
    v-model="keyword"
    class="font-mono"
/>
```

`BaseInput`은 일반 HTML 속성과 class를 실제 input에 전달합니다.

## 특수 CSS

가상 요소, 복잡한 애니메이션 등은 일반 CSS로 추가할 수 있습니다.

```css
.analysis-progress-line::before {
    position: absolute;
    content: '';
    width: 1px;
    background: linear-gradient(to bottom, #3b82f6, #cbd5e1);
}
```

---

# 16. 새 공통 컴포넌트 작성 기준

다음 조건 중 두 개 이상에 해당하면 공통 컴포넌트 분리를 고려합니다.

- 두 화면 이상에서 반복됩니다.
- 동일한 스타일을 유지해야 합니다.
- 로딩, 오류, 비활성화 같은 동작이 반복됩니다.
- 접근성 속성을 한곳에서 통제해야 합니다.
- 수정 시 여러 화면을 동시에 바꿔야 합니다.

단순한 배치 하나는 그대로 유지합니다.

```vue
<div class="flex items-center justify-between">
```

다음처럼 의미 없는 래퍼 컴포넌트를 남발하지 않습니다.

```vue
<FlexBetween>
```

## 권장 예시

```text
BaseModal.vue
BaseCheckbox.vue
BaseRadioGroup.vue
BaseDateInput.vue
ConfirmDialog.vue
EmptyState.vue
LoadingState.vue
```

## 컴포넌트 작성 형태

```vue
<script setup>
defineProps({
    title: {
        type: String,
        default: '',
    },
})

defineEmits(['confirm', 'cancel'])
</script>

<template>
    <section>
        <slot name="header">
            {{ title }}
        </slot>

        <slot />
    </section>
</template>
```

원본 내부에 업무 API나 특정 화면 이름을 넣지 않고 데이터와 이벤트를 외부에서 받도록 작성합니다.

---

# 17. 권장 화면 작성 예시

프로젝트 목록 화면의 일반적인 형태입니다.

```vue
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BasePagination from '@/components/base/BasePagination.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { projectApi } from '@/api/projectApi'
import { useAuthStore } from '@/stores/authStore'

const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const rows = ref([])
const totalCount = ref(0)

const search = reactive({
    keyword: '',
    status: '',
    page: 0,
    size: 20,
    sortKey: 'updatedAt',
    sortDirection: 'desc',
})

const statusOptions = [
    { label: '전체 상태', value: '' },
    { label: '활성', value: 'ACTIVE' },
    { label: '보관', value: 'ARCHIVED' },
]

const columns = computed(() => [
    { key: 'code', label: '코드', width: '130px' },
    { key: 'name', label: '프로젝트명', minWidth: '260px' },
    { key: 'status', label: '상태', width: '110px', align: 'center' },
    {
        key: 'updatedAt',
        label: '수정일시',
        width: '180px',
        align: 'center',
        sortable: true,
    },
    {
        key: 'actions',
        label: '관리',
        width: '100px',
        align: 'center',
        visible: authStore.hasPermission('PROJECT_UPDATE'),
    },
])

const fetchProjects = async () => {
    loading.value = true

    try {
        const { data } = await projectApi.getProjects(search)
        rows.value = data.content
        totalCount.value = data.totalElements
    } finally {
        loading.value = false
    }
}

const handleSearch = () => {
    search.page = 0
    fetchProjects()
}

const handleSort = ({ key, direction }) => {
    search.sortKey = key
    search.sortDirection = direction
    search.page = 0
    fetchProjects()
}

const openDetail = (row) => {
    router.push({
        name: 'project-detail',
        params: {
            projectId: row.id,
        },
    })
}

onMounted(fetchProjects)
</script>

<template>
    <ViewHeading
        title="프로젝트 관리"
        description="분석 대상 프로젝트를 관리합니다."
    >
        <template #actions>
            <BaseButton
                v-if="authStore.hasPermission('PROJECT_CREATE')"
                :to="{ name: 'project-register' }"
            >
                프로젝트 등록
            </BaseButton>
        </template>
    </ViewHeading>

    <div class="mb-4 grid grid-cols-[1fr_180px_auto] gap-3">
        <BaseInput
            v-model="search.keyword"
            placeholder="프로젝트명 또는 코드 검색"
            @keyup.enter="handleSearch"
        />

        <BaseSelect
            v-model="search.status"
            :options="statusOptions"
        />

        <BaseButton variant="secondary" @click="handleSearch">
            조회
        </BaseButton>
    </div>

    <BaseTable
        :columns="columns"
        :rows="rows"
        :loading="loading"
        :sort-key="search.sortKey"
        :sort-direction="search.sortDirection"
        clickable
        @row-click="openDetail"
        @sort-change="handleSort"
    >
        <template #cell-name="{ row }">
            <div>
                <div class="font-bold text-slate-900 dark:text-slate-100">
                    {{ row.name }}
                </div>
                <div class="mt-1 text-xs text-slate-400">
                    {{ row.description }}
                </div>
            </div>
        </template>

        <template #cell-status="{ value }">
            <StatusBadge :status="value" />
        </template>

        <template #cell-actions="{ row }">
            <BaseButton
                size="small"
                variant="ghost"
                @click="openDetail(row)"
            >
                상세
            </BaseButton>
        </template>

        <template #footer>
            <BasePagination
                v-model:page="search.page"
                :page-size="search.size"
                :total-count="totalCount"
                @change="fetchProjects"
            />
        </template>
    </BaseTable>
</template>
```

---

# 18. 자주 발생하는 문제

## 데이터가 있는데 행이 안 보이는 경우

`columns.key`와 API 응답 필드명이 같은지 확인합니다.

```js
// 컬럼
{ key: 'projectName', label: '프로젝트명' }

// 행
{
    projectName: '공공 마이데이터',
}
```

필드명이 다르면 `value`를 사용합니다.

```js
{
    key: 'projectName',
    label: '프로젝트명',
    value: (row) => row.name,
}
```

## 행 선택이 동작하지 않는 경우

각 행에 고유한 키가 있는지 확인합니다.

```vue
<BaseTable row-key="projectId" />
```

## 정렬 아이콘만 바뀌고 데이터가 정렬되지 않는 경우

`BaseTable`은 정렬 이벤트만 발생시킵니다. 다음 중 하나가 필요합니다.

- `useClientTable`로 프론트 정렬
- `sort-change`에서 서버 API 재호출

## API 결과가 한 페이지 객체인 경우

Spring Data 응답에서는 배열 전체가 아니라 `content`를 전달합니다.

```js
rows.value = response.data.content
totalCount.value = response.data.totalElements
```

## 버튼을 눌렀는데 행 상세로 이동하는 경우

현재 `BaseTable`은 `button`, `a`, `input`, `select`, `textarea`, `[role="button"]` 클릭을 행 클릭에서 제외합니다. 사용자 정의 클릭 요소에는 `role="button"`을 지정합니다.

```vue
<div role="button" tabindex="0" @click="openMenu">
    메뉴
</div>
```

## Tailwind 클래스가 생성되지 않는 경우

동적 문자열로 완전한 클래스명을 조립하지 않습니다.

피해야 할 방식:

```js
const color = 'blue'
const buttonClass = `bg-${color}-600`
```

권장 방식:

```js
const variants = {
    blue: 'bg-blue-600',
    rose: 'bg-rose-600',
}
```

---

# 19. 확인 명령어

```bash
npm run lint
npm run format:check
npm run build
```

자동 수정:

```bash
npm run lint:fix
npm run format
```

---

# 20. 핵심 정리

```text
BaseButton
→ 버튼 모양, 크기, 로딩, 링크 재사용

BaseInput / BaseSelect / BaseTextarea
→ 라벨, 필수, 안내, 오류 디자인 재사용

BaseCard
→ 패널 구조와 header/actions/footer 재사용

BaseTable
→ 데이터 수에 따른 행 자동 증감
→ 화면별 컬럼 설정
→ 슬롯을 이용한 셀 변경
→ 정렬, 선택, 행 클릭, 고정 헤더 지원

BasePagination
→ 0부터 시작하는 페이지 이동

useClientTable
→ 소량 데이터의 프론트 정렬과 페이지 처리

Tailwind CSS
→ 공통 디자인 토큰과 ui-* 클래스 관리
```

공통 컴포넌트는 수정 지점을 줄이고 화면 간 디자인과 동작을 일관되게 유지하기 위한 기반입니다. 화면에서 원본 컴포넌트를 수정하거나 복사하기보다 `props`, `slot`, 이벤트로 확장하는 방식을 사용합니다.
