// 날짜 입력은 사용자의 현지 날짜이므로 등록 시각도 같은 날짜 기준으로 비교한다.
export const toLocalDateKey = (value) => {
    if (!value) return ''
    if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value

    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return ''

    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
}

export const matchesCreatedDateRange = (createdAt, startDate, endDate) => {
    if (!startDate && !endDate) return true
    const date = toLocalDateKey(createdAt)
    if (!date) return false
    return (!startDate || date >= startDate) && (!endDate || date <= endDate)
}
