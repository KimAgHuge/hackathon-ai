from fastapi import FastAPI

app = FastAPI(title='doenaai ai server')


@app.get('/health')
def health():
    return {
        'status': 'UP',
        'service': 'doenaai-ai-server',
        'runtime': 'Mac Studio',
    }
