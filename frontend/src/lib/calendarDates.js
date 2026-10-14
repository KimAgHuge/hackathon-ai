export const toDateKey = (date) => [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-')

export const parseDateKey = (value) => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return null
    const [year, month, day] = value.split('-').map(Number)
    const date = new Date(year, month - 1, day)
    return toDateKey(date) === value ? date : null
}

// 월요일을 첫 칸으로 하고 앞뒤 달의 날짜도 함께 표시한다.
export const calendarDays = (year, month) => {
    const firstWeekday = (new Date(year, month - 1, 1).getDay() + 6) % 7
    const daysInMonth = new Date(year, month, 0).getDate()
    const cellCount = Math.ceil((firstWeekday + daysInMonth) / 7) * 7

    return Array.from({ length: cellCount }, (_, index) => {
        const date = new Date(year, month - 1, index - firstWeekday + 1)
        return {
            key: toDateKey(date),
            day: date.getDate(),
            currentMonth: date.getMonth() === month - 1,
        }
    })
}
