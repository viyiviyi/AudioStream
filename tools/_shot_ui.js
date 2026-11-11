// 交互态截图：编辑面板、日志展开。只在开发时用。
const fs = require('fs');
const path = require('path');
const CDP = process.argv[2] || 'http://127.0.0.1:9223';
const OUT = process.argv[3] || path.join(__dirname, '_ui_states');

function connect(url) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(url);
        let id = 0;
        const pending = new Map();
        ws.addEventListener('open', () => resolve({
            send(method, params) {
                const mid = ++id;
                ws.send(JSON.stringify({ id: mid, method, params: params || {} }));
                return new Promise((res, rej) => pending.set(mid, { res, rej }));
            },
            close() { ws.close(); }
        }));
        ws.addEventListener('error', () => reject(new Error('WebSocket 连接失败')));
        ws.addEventListener('message', ev => {
            const msg = JSON.parse(ev.data);
            if (msg.id && pending.has(msg.id)) {
                const p = pending.get(msg.id);
                pending.delete(msg.id);
                if (msg.error) p.rej(new Error(JSON.stringify(msg.error)));
                else p.res(msg.result);
            }
        });
    });
}

async function shot(client, file) {
    const res = await client.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    fs.writeFileSync(file, Buffer.from(res.data, 'base64'));
    console.log('saved ' + file + ' (' + fs.statSync(file).size + ' bytes)');
}

(async () => {
    const list = await (await fetch(CDP + '/json/list')).json();
    const page = list.find(t => t.type === 'page' && /index\.html/.test(t.url || ''));
    if (!page) throw new Error('找不到页面');
    const client = await connect(page.webSocketDebuggerUrl);
    await client.send('Page.enable');
    await client.send('Runtime.enable');

    await client.send('Runtime.evaluate', {
        expression: `document.querySelector('.player-row .edit-btn').click();`
    });
    await new Promise(r => setTimeout(r, 800));
    await shot(client, path.join(OUT, 'edit.png'));

    await client.send('Runtime.evaluate', {
        expression: `document.getElementById('edit-cancel-btn').click();
            document.getElementById('log-toggle').click();`
    });
    await new Promise(r => setTimeout(r, 1500));
    await shot(client, path.join(OUT, 'log.png'));

    await client.send('Runtime.evaluate', { expression: `document.getElementById('log-toggle').click();` });
    client.close();
    process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
