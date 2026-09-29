// 来源下拉里两个「跟随系统默认」项的自检：用 CDP 驱动无头 Chrome 真选一次、真添加一条。
// 只在开发时用，不参与产品运行。
//
// 用法：
//   1) 先起程序（Debug 版），再起无头浏览器（独立 profile）：
//      & "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu `
//        --remote-debugging-port=9222 --user-data-dir=<临时目录> http://127.0.0.1:12570/
//   2) node tools\_ui_default_source.js
//
// 这个脚本会真的建一条本机流转的拉取（来源 = 系统默认输入设备，播放目标挑一块虚拟声卡），
// 确认建得起来之后立刻把它删掉，不留痕迹。之所以敢真建：两边都是虚拟线，
// 采的是虚拟输入、放的是虚拟输出，不会有声音从真实喇叭出来。
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
        const events = [];
        ws.addEventListener('open', () => resolve({
            send(method, params) {
                const mid = ++id;
                ws.send(JSON.stringify({ id: mid, method, params: params || {} }));
                return new Promise((res, rej) => pending.set(mid, { res, rej }));
            },
            events,
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
            } else if (msg.method) {
                events.push(msg);
            }
        });
    });
}

const PAGE_SCRIPT = `(async () => {
  const steps = [];
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const $ = id => document.getElementById(id);
  const add = (name, pass, extra) => steps.push({ name: name, pass: !!pass, extra: extra === undefined ? '' : String(extra) });
  const waitFor = async (fn, ms) => {
    const until = Date.now() + ms;
    while (Date.now() < until) { if (await fn()) return true; await sleep(200); }
    return false;
  };
  const players = async () => ((await (await fetch('/api/players')).json()).Result || []);
  const boxes = () => Array.from($('new-local-devices').querySelectorAll('input[type=checkbox]'));

  const select = $('new-remote-device');
  await waitFor(() => select.options.length > 0, 15000);

  // ---- 1. 下拉里的两个跟随项 ----
  const groups = Array.from(select.querySelectorAll('optgroup'));
  const follow = groups.find(g => (g.label || '').indexOf('跟随系统默认') >= 0);
  add('来源下拉里有「跟随系统默认」分组', !!follow, follow ? follow.label : groups.map(g => g.label).join(' / '));
  add('分组排在真实设备分组之前', !!follow && groups[0] === follow && select.firstElementChild === follow,
      '第一个子元素是分组：' + (select.firstElementChild && select.firstElementChild.tagName));

  const items = follow ? Array.from(follow.querySelectorAll('option')) : [];
  add('分组里正好两项', items.length === 2, items.map(o => o.value).join('、'));
  const defOut = items.find(o => o.value === 'default');
  const defIn = items.find(o => o.value === 'default-input');
  add('有系统默认输出设备项', !!defOut, defOut ? defOut.textContent : '');
  add('有系统默认输入设备项', !!defIn, defIn ? defIn.textContent : '');
  add('两项带可读的中文名字', !!defOut && defOut.dataset.name === '系统默认输出设备'
      && !!defIn && defIn.dataset.name === '系统默认输入设备',
      (defOut ? defOut.dataset.name : '') + ' / ' + (defIn ? defIn.dataset.name : ''));

  // 设备枚举为空时那个占位项必须点不动，否则会被当成真设备选走
  const placeholder = Array.from(select.options).find(o => o.value === '' && !o.parentElement.tagName.match(/OPTGROUP/i));
  add('设备为空时的占位项是禁用的', !placeholder || placeholder.disabled,
      placeholder ? ('disabled=' + placeholder.disabled + ' 文本=' + placeholder.textContent) : '没有占位项');

  // ---- 2. 真选一次系统默认输入设备 ----
  select.value = 'default-input';
  const selected = select.selectedOptions[0];
  add('能选中系统默认输入设备', !!selected && selected.value === 'default-input',
      selected ? selected.textContent : '选不中');
  add('选中项对外的名字是纯名字', !!selected && selected.dataset.name === '系统默认输入设备',
      selected ? selected.dataset.name : '');
  add('选完没有跑掉', select.value === 'default-input', select.value);

  // ---- 3. 真建一条本机流转的拉取，然后立刻删掉 ----
  const ready = await waitFor(() => boxes().length > 1, 15000);
  add('播放设备列表已加载', ready, boxes().length + ' 个候选');
  const target = boxes().find(b => (b.dataset.name || '').indexOf('Virtual Speakers') >= 0)
      || boxes().find(b => b.value === 'default')
      || boxes()[0];
  add('找到可勾选的播放设备', !!target, target ? (target.dataset.name + '（' + target.value + '）') : '');
  if (target) target.click();
  await sleep(200);

  const before = (await players()).length;
  $('add-player-btn').click();
  const created = await waitFor(async () => (await players()).some(p => p.SourceDeviceID === 'default-input'), 15000);
  const list = await players();
  const mine = list.find(p => p.SourceDeviceID === 'default-input');
  add('点添加后真的建起了这条拉取', created, '拉取数 ' + before + ' → ' + list.length);
  add('落盘的来源设备号是 default-input', !!mine, mine ? mine.SourceDeviceID : '没找到');
  add('落盘的来源名字是「系统默认输入设备」', !!mine && mine.SourceDeviceName === '系统默认输入设备',
      mine ? String(mine.SourceDeviceName) : '');
  add('这条拉取的 IP 是空的（本机流转）', !!mine && !mine.IP, mine ? JSON.stringify(mine.IP) : '');

  // 收尾：删掉刚才那条，别在用户机器上留东西
  if (mine) {
    await fetch('/api/del?id=' + encodeURIComponent(mine.ID), { method: 'POST' });
  }
  const cleaned = await waitFor(async () => !(await players()).some(p => p.SourceDeviceID === 'default-input'), 10000);
  add('收尾把测试拉取删干净了', cleaned, '剩余拉取 ' + (await players()).length + ' 条');

  // ---- 4. 换来源时不该把这个选择弄丢 ----
  select.value = 'default';
  select.value = 'default-input';
  add('两项之间来回切都留得住', select.value === 'default-input', select.value);

  add('页面无未捕获异常', !window.__uiDefaultSourceError, window.__uiDefaultSourceError || '');
  return steps;
})()`;

async function main() {
    const page = await findPage();
    const client = await connect(page.webSocketDebuggerUrl);
    await client.send('Runtime.enable');
    await client.send('Page.enable');
    await client.send('Page.navigate', { url: 'http://127.0.0.1:12570/' });
    await new Promise(r => setTimeout(r, 4000));
    await client.send('Runtime.evaluate', {
        expression: `window.__uiDefaultSourceError='';
            window.addEventListener('error', e => { window.__uiDefaultSourceError = String(e.message); });
            window.addEventListener('unhandledrejection', e => { window.__uiDefaultSourceError = String(e.reason); });`
    });

    const res = await client.send('Runtime.evaluate', {
        expression: PAGE_SCRIPT,
        awaitPromise: true,
        returnByValue: true
    });
    if (res.exceptionDetails) {
        console.error('页面脚本抛异常：', JSON.stringify(res.exceptionDetails, null, 2));
        process.exit(2);
    }

    const steps = res.result.value || [];
    let failed = 0;
    steps.forEach(s => {
        if (!s.pass) failed++;
        console.log((s.pass ? '通过' : '失败') + ' | ' + s.name + (s.extra ? ' | ' + s.extra : ''));
    });

    const consoleErrors = client.events
        .filter(e => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
        .map(e => (e.params.args || []).map(a => a.value || a.description || '').join(' '));
    const exceptions = client.events
        .filter(e => e.method === 'Runtime.exceptionThrown')
        .map(e => (e.params.exceptionDetails && e.params.exceptionDetails.text) || '');
    if (consoleErrors.length || exceptions.length) {
        console.log('--- 浏览器控制台报错 ---');
        consoleErrors.concat(exceptions).forEach(t => console.log('  ' + t));
        failed += consoleErrors.length + exceptions.length;
    } else {
        console.log('浏览器控制台：没有报错');
    }

    console.log('合计 ' + steps.length + ' 项，失败 ' + failed + ' 项');
    client.close();
    process.exit(failed ? 1 : 0);
}

main().catch(e => { console.error('自检脚本自身出错：', e); process.exit(2); });
