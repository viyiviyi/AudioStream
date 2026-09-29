// 配置页界面自检：确认「我拉的」每一行真的把音质数字渲染出来了。
// 只在开发时用，不参与产品运行。
//
// 用法：
//   1) 先起程序，并造一条真的在播的拉取（本机流转：来源=麦克风、目标=虚拟扬声器、音量 0），
//      否则 Quality 是 null、界面上本来就不该有这一行。
//   2) 起无头浏览器（独立 profile）：
//      & "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu `
//        --remote-debugging-port=9222 --user-data-dir=<临时目录> http://127.0.0.1:12570/
//   3) node tools\_ui_quality.js
//
// 只读页面，不做任何点击与写入。
const CDP = process.argv[2] || 'http://127.0.0.1:9222';

async function findPage() {
    const list = await (await fetch(CDP + '/json/list')).json();
    const page = list.find(t => t.type === 'page' && (t.url || '').indexOf('12570') >= 0)
        || list.find(t => t.type === 'page');
    if (!page) throw new Error('找不到可用的页面 target');
    return page;
}

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

const PAGE_SCRIPT = `(() => {
  const rows = Array.from(document.querySelectorAll('.player-row'));
  const lines = Array.from(document.querySelectorAll('.quality-line')).map(e => e.innerText.trim());
  const cell = document.querySelector('.player-row .col-state');
  const badge = document.querySelector('.player-row .col-state .badge-text');
  return {
    rows: rows.length,
    badgeText: badge ? badge.innerText.trim() : '',
    stateText: cell ? cell.innerText.replace(/\\s+/g, ' ').trim() : '',
    qualityCount: lines.length,
    quality: lines[0] || '',
    badCount: document.querySelectorAll('.quality-line.bad').length
  };
})()`;

(async () => {
    const page = await findPage();
    const cdp = await connect(page.webSocketDebuggerUrl);
    await cdp.send('Runtime.enable');

    // 状态是 3 秒一轮，等凉数据变成有水位的数据再断言
    let value = null;
    for (let i = 0; i < 20; i++) {
        const r = await cdp.send('Runtime.evaluate', { expression: PAGE_SCRIPT, returnByValue: true });
        value = r.result.value;
        if (value && value.qualityCount > 0) break;
        await new Promise(res => setTimeout(res, 500));
    }
    cdp.close();

    const checks = [];
    const add = (name, ok, extra) => checks.push({ name, ok: !!ok, extra: extra === undefined ? '' : String(extra) });

    add('播放列表有记录', value.rows > 0, '行数 ' + value.rows);
    add('状态徽标已渲染', !!value.badgeText, value.badgeText);
    add('音质行已渲染', value.qualityCount > 0, '条数 ' + value.qualityCount);
    add('音质文字含缓冲水位', /缓冲\s*\d+\/\d+ms/.test(value.quality), value.quality);
    add('音质文字含欠载口径', /欠载|无欠载/.test(value.quality), value.quality);
    add('音质行不超过一行高度', value.stateText.split('缓冲').length <= 2, value.stateText);

    let failed = 0;
    for (const c of checks) {
        if (c.ok) {
            console.log('[通过] ' + c.name + (c.extra ? '  -> ' + c.extra : ''));
        } else {
            failed++;
            console.log('[失败] ' + c.name + '  -> ' + c.extra);
        }
    }
    console.log('通过 ' + (checks.length - failed) + '/' + checks.length);
    process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
    console.log('[失败] 脚本异常：' + e.message);
    process.exit(1);
});
