// 配置页界面自检：用 CDP 驱动无头 Chrome 真点一遍关键交互。
// 只在开发时用，不参与产品运行。
//
// 用法：
//   1) 先起无头浏览器（独立 profile，别用日常那个）：
//      & "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu `
//        --remote-debugging-port=9222 --user-data-dir=<临时目录> http://127.0.0.1:12570/
//   2) node tools\_ui_click.js
//
// 安全约定：全程不碰真实发声路径——
//   「添加」用不可达地址 203.0.113.9（TEST-NET-3）当对端、来源设备是注入的虚构设备号，
//   播放目标只勾「Virtual Speakers」这类虚拟声卡，不会从用户耳朵里出声。
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
        ws.addEventListener('error', e => reject(new Error('WebSocket 连接失败')));
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

// 页面里跑的一整段交互。返回逐步结论，任何一步炸掉都带上下文回来。
const PAGE_SCRIPT = `(async () => {
  const steps = [];
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const $ = id => document.getElementById(id);
  const rows = () => Array.from(document.querySelectorAll('.player-row'));
  const toast = () => ($('toast-wrap') ? $('toast-wrap').textContent.trim() : '');
  const add = (name, pass, extra) => steps.push({ name: name, pass: !!pass, extra: extra === undefined ? '' : String(extra) });
  const waitFor = async (fn, ms) => {
    const until = Date.now() + ms;
    while (Date.now() < until) { if (fn()) return true; await sleep(150); }
    return false;
  };

  // 等首页第一轮加载完
  await waitFor(() => rows().length > 0 && $('info-machineid').textContent !== '—', 15000);
  const startCount = rows().length;
  add('播放列表已渲染', startCount === 3, '行数 ' + startCount);
  add('本机信息已填充', $('info-pcname').textContent === 'XPC' && $('info-machineid').textContent.length === 32,
      $('info-pcname').textContent + ' / ' + $('info-machineid').textContent);
  add('本机地址已列出', $('info-addresses').textContent.indexOf('.') > 0, $('info-addresses').textContent.trim());

  // ---------- 1. 改目标与策略：挑一条没在跑的记录，不会触发真实播放 ----------
  const idleRow = rows().find(r => r.textContent.indexOf('本机默认输出') >= 0);
  add('找到待改的记录', !!idleRow);
  idleRow.querySelector('.edit-btn').click();
  const panelOpen = await waitFor(() => $('edit-panel') && !$('edit-panel').classList.contains('hidden'), 3000);
  add('编辑面板已打开', panelOpen);
  add('编辑面板带出当前策略', $('edit-policy').value === 'Failover' || $('edit-policy').value === 'All',
      $('edit-policy').value);
  add('编辑面板带出当前目标',
      Array.from($('edit-local-devices').querySelectorAll('input[type=checkbox]')).filter(b => b.checked).length === 2,
      Array.from($('edit-local-devices').querySelectorAll('input[type=checkbox]')).filter(b => b.checked).length + ' 个勾选');
  // 策略换个值存回去：这个脚本要能反复跑，不能指望一开始正好是哪一个
  const beforePolicy = $('edit-policy').value;
  const expectPolicy = beforePolicy === 'Failover' ? 'All' : 'Failover';
  const expectText = expectPolicy === 'All' ? '全部同时播放' : '按顺序主备';
  $('edit-policy').value = expectPolicy;
  $('edit-save-btn').click();
  add('保存后有提示', await waitFor(() => toast().length > 0, 5000), toast());
  const idleRow2 = rows().find(r => r.textContent.indexOf('本机默认输出') >= 0);
  add('该行策略已按保存值更新', !!idleRow2 && idleRow2.textContent.indexOf(expectText) >= 0,
      idleRow2 ? idleRow2.textContent.replace(/\\s+/g, ' ').slice(0, 90) : '行不见了');

  // ---------- 1b. 播放设备列表的就地刷新：勾选与勾选顺序都不能被刷掉 ----------
  // 放在第 2 节之前：刷新会重填来源下拉，会把下面注入的虚构来源设备冲掉
  const pickBoxes = () => Array.from($('new-local-devices').querySelectorAll('input[type=checkbox]'));
  const pickChecked = () => pickBoxes().filter(b => b.checked);
  const refreshBox = pickBoxes().find(b => (b.dataset.name || '').indexOf('Virtual Speakers') >= 0);
  add('刷新前能选到虚拟声卡', !!refreshBox, refreshBox ? refreshBox.dataset.name : '');
  if (refreshBox) refreshBox.click();
  await sleep(200);
  const listBefore = pickBoxes().length;
  $('local-devices-refresh').click();
  await sleep(2000);
  add('点刷新后设备列表还在', pickBoxes().length === listBefore, pickBoxes().length + ' 个候选（刷新前 ' + listBefore + '）');
  add('刷新后已勾选的设备没被刷掉', pickChecked().length === 1,
      '勾选 ' + pickChecked().length + ' 个：' + pickChecked().map(b => b.dataset.name).join('、'));
  add('刷新后勾选顺序仍显示', $('new-target-order').textContent.indexOf('已选顺序') >= 0, $('new-target-order').textContent.trim());
  // 清掉刚才试刷时勾的，别让它混进下一节的添加流程
  pickChecked().forEach(b => b.click());
  await sleep(200);

  // ---------- 2. 添加一条拉取：对端不可达、目标用虚拟声卡，只验「按钮→接口→列表」这条链 ----------
  $('new-ip').value = '203.0.113.9';
  $('new-ip').dispatchEvent(new Event('blur'));
  await sleep(6500); // 远程设备拉不到，等它超时给出「对方没有可用设备」
  // 注入一个虚构来源设备：本机没有能安全拉的对端，这里替页面补上"对端设备已加载"的状态
  const sel = $('new-remote-device');
  sel.innerHTML = '';
  const fake = document.createElement('option');
  fake.value = '{0.0.0.00000000}.{ui-check-fake-source}';
  fake.textContent = '虚构来源设备（界面自检）';
  fake.dataset.name = '虚构来源设备（界面自检）';
  sel.appendChild(fake);
  sel.value = fake.value;
  $('new-policy').value = 'All';

  const boxes = Array.from($('new-local-devices').querySelectorAll('input[type=checkbox]'));
  const virtual = boxes.find(b => (b.dataset.name || '').indexOf('Virtual Speakers') >= 0);
  add('勾选到虚拟声卡当目标', !!virtual, boxes.map(b => b.dataset.name).join(' / '));
  if (virtual) virtual.click();
  add('勾选顺序已显示', $('new-target-order').textContent.indexOf('已选顺序') >= 0, $('new-target-order').textContent.trim());

  $('add-player-btn').click();
  const added = await waitFor(() => rows().length === startCount + 1, 8000);
  add('点添加后列表多了一行', added, '行数 ' + rows().length + ' / 提示「' + toast() + '」');
  const newRow = rows().find(r => r.textContent.indexOf('203.0.113.9') >= 0);
  add('新行的对端地址正确', !!newRow);
  add('新行策略为全部同时播放', !!newRow && newRow.textContent.indexOf('全部同时播放') >= 0);

  // 收掉刚才这条：点删除 -> 确认框 -> 确认
  if (newRow) {
    newRow.querySelector('.delete-btn').click();
    await waitFor(() => $('confirm-dialog') && !$('confirm-dialog').classList.contains('hidden'), 3000);
    add('删除确认框已出现', !$('confirm-dialog').classList.contains('hidden'));
    $('confirm-btn').click();
    add('删除后行数回到原样', await waitFor(() => rows().length === startCount, 8000), '行数 ' + rows().length);
  }

  // ---------- 3. 拉取授权：加一条规则再删掉 ----------
  $('grant-machine').value = 'ui-check-machine';
  $('grant-device').value = '';
  $('grant-access').value = '永不允许';
  $('grant-add-btn').click();
  const grantShown = await waitFor(() => $('grant-list').textContent.indexOf('ui-check-machine') >= 0, 6000);
  add('新规则出现在规则列表里', grantShown, $('grant-list').textContent.replace(/\\s+/g, ' ').slice(0, 90));
  if (grantShown) {
    const item = Array.from($('grant-list').querySelectorAll('.grant-delete'))
        .find(b => b.closest('.grant-line').textContent.indexOf('ui-check-machine') >= 0);
    add('找到该规则的删除按钮', !!item);
    if (item) {
      item.click();
      add('删规则后条目消失',
          await waitFor(() => $('grant-list').textContent.indexOf('ui-check-machine') < 0, 6000),
          $('grant-list').textContent.replace(/\\s+/g, ' ').slice(0, 90));
    }
  }

  // ---------- 4. 日志面板 ----------
  $('log-toggle').click();
  const logShown = await waitFor(() => $('log-body') && !$('log-body').classList.contains('hidden')
      && $('log-box').textContent.length > 50, 8000);
  add('日志面板展开后读到内容', logShown, $('log-box').textContent.replace(/\\s+/g, ' ').slice(0, 80));
  add('日志里有程序自己的记录', $('log-box').textContent.indexOf('AudioStream') >= 0);

  // ---------- 5. 页面自己没报错 ----------
  add('页面无未捕获异常', !window.__uiCheckError, window.__uiCheckError || '');

  return steps;
})()`;

async function main() {
    const page = await findPage();
    const client = await connect(page.webSocketDebuggerUrl);
    await client.send('Runtime.enable');
    await client.send('Page.enable');
    // 每轮都从干净的页面开始：上一轮跑完可能留着打开的编辑面板、填了一半的表单
    await client.send('Page.navigate', { url: 'http://127.0.0.1:12570/' });
    await new Promise(r => setTimeout(r, 4000));
    // 页面里的异常与 console.error 都记下来，最后一起看
    await client.send('Runtime.evaluate', {
        expression: `window.__uiCheckError='';
            window.addEventListener('error', e => { window.__uiCheckError = String(e.message); });
            window.addEventListener('unhandledrejection', e => { window.__uiCheckError = String(e.reason); });`
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
