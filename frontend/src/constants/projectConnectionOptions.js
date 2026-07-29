export const SOURCE_TYPES = [
    {
        value: 'GIT',
        label: 'Git 저장소',
        available: true,
    },
    {
        value: 'ZIP',
        label: 'ZIP 업로드',
        available: false,
    },
]

export const GIT_PROVIDER_TYPES = [
    {
        value: 'AUTO',
        label: 'URL에서 자동 판별',
    },
    {
        value: 'GITHUB',
        label: 'GitHub',
    },
    {
        value: 'GITLAB',
        label: 'GitLab',
    },
    {
        value: 'BITBUCKET',
        label: 'Bitbucket',
    },
    {
        value: 'YONA',
        label: 'Yona',
    },
    {
        value: 'CUSTOM',
        label: '사내·기타 Git 서버',
    },
]

export const GIT_AUTH_TYPES = [
    {
        value: 'NONE',
        label: '인증 없음 (공개 HTTPS)',
    },
    {
        value: 'HTTPS_TOKEN',
        label: 'HTTPS 사용자명 + Token',
    },
    {
        value: 'HTTPS_BASIC',
        label: 'HTTPS 사용자명 + 비밀번호',
    },
    {
        value: 'SSH_KEY',
        label: 'SSH 개인키',
    },
    {
        value: 'PROVIDER_APP',
        label: '공급자 App 연동 (준비 중)',
        disabled: true,
    },
]

export const DATABASE_MODES = [
    {
        value: 'DB_CONNECTION',
        label: 'DB 연결',
        available: true,
    },
    {
        value: 'DDL_UPLOAD',
        label: 'DDL 업로드',
        available: false,
    },
]

export const DATABASE_TYPES = [
    {
        value: 'ORACLE',
        label: 'Oracle',
        defaultPort: 1521,
        databaseNameLabel: '서비스명',
        databaseNamePlaceholder: '예: ORCL',
        disabled: false,
    },
    {
        value: 'POSTGRESQL',
        label: 'PostgreSQL',
        defaultPort: 5432,
        databaseNameLabel: '데이터베이스명',
        databaseNamePlaceholder: '예: postgres',
        disabled: false,
    },
    {
        value: 'TIBERO',
        label: 'Tibero (준비 중)',
        defaultPort: 8629,
        databaseNameLabel: 'DB명',
        databaseNamePlaceholder: '예: tibero',
        disabled: true,
    },
    {
        value: 'MYSQL',
        label: 'MySQL (준비 중)',
        defaultPort: 3306,
        databaseNameLabel: '데이터베이스명',
        databaseNamePlaceholder: '예: doenaai',
        disabled: true,
    },
    {
        value: 'MARIADB',
        label: 'MariaDB (준비 중)',
        defaultPort: 3306,
        databaseNameLabel: '데이터베이스명',
        databaseNamePlaceholder: '예: doenaai',
        disabled: true,
    },
    {
        value: 'MSSQL',
        label: 'SQL Server (준비 중)',
        defaultPort: 1433,
        databaseNameLabel: '데이터베이스명',
        databaseNamePlaceholder: '예: doenaai',
        disabled: true,
    },
]

export const getDatabaseTypeOption = (databaseType) => DATABASE_TYPES.find(({ value }) => value === databaseType)
