const $ = id => document.getElementById(id);
const status = message => $('status').textContent = message;
const size = bytes => bytes < 1024 ? `${bytes} B` : bytes < 1048576 ? `${(bytes/1024).toFixed(1)} KB` : `${(bytes/1048576).toFixed(1)} MB`;
async function refresh() {
  const response = await fetch('/api/files');
  if (response.status === 401) { $('pair').hidden = false; $('transfer').hidden = true; return; }
  if (!response.ok) throw new Error('Could not load shared files.');
  const files = await response.json();
  $('pair').hidden = true; $('transfer').hidden = false; $('count').textContent = files.length;
  $('listing').replaceChildren();
  if (!files.length) { const empty = document.createElement('div'); empty.className = 'empty'; empty.textContent = 'A fresh start. Send your first file above.'; $('listing').append(empty); }
  for (const file of files) {
    const row = document.createElement('div'); row.className = 'file';
    const icon = document.createElement('span'); icon.className = 'icon'; icon.textContent = file.folder ? '▱' : '▤';
    const name = document.createElement('div'); name.className = 'name'; name.textContent = file.path;
    const detail = document.createElement('small'); detail.textContent = file.folder ? 'Folder · Download as ZIP' : size(file.size); name.append(detail);
    const link = document.createElement('a'); link.href = '/api/download?path=' + encodeURIComponent(file.path); link.textContent = '↓ Save'; link.setAttribute('aria-label', 'Download ' + file.path);
    row.append(icon, name, link); $('listing').append(row);
  }
}
$('pair-form').onsubmit = async event => {
  event.preventDefault();
  try {
    const response = await fetch('/api/pair', {method:'POST', headers:{'Content-Type':'application/json','X-ShareMe':'1'}, body:JSON.stringify({code:$('code').value.trim().toUpperCase()})});
    if (!response.ok) throw new Error((await response.json()).error);
    status('Device paired. Ready to transfer.'); await refresh();
  } catch (error) { status(error.message); }
};
let sending = false;
async function send(files) {
  if (sending || !files.length) return;
  sending = true; $('progress-area').hidden = false;
  $('choose-files').disabled = $('choose-folder').disabled = true;
  try {
    let completed = 0;
    for (const file of files) {
      const path = file.webkitRelativePath || file.name;
      $('progress-label').textContent = `${completed + 1} of ${files.length} · ${path}`;
      $('progress').value = 0;
      await new Promise((resolve, reject) => {
        const request = new XMLHttpRequest();
        request.open('POST', '/api/upload?path=' + encodeURIComponent(path));
        request.setRequestHeader('X-ShareMe','1');
        request.upload.onprogress = event => { if (event.lengthComputable) $('progress').value = event.loaded/event.total*100; };
        request.onload = () => request.status === 201 ? resolve() : reject(new Error('Transfer failed. Check the connection and pairing code.'));
        request.onerror = () => reject(new Error('Connection lost. Reconnect and try again.'));
        request.send(file);
      });
      completed++;
    }
    status(`${files.length} file${files.length === 1 ? '' : 's'} sent successfully.`);
    await refresh();
  } catch (error) { status(error.message); }
  finally { sending = false; $('choose-files').disabled = $('choose-folder').disabled = false; $('progress-area').hidden = true; $('files').value = $('folder').value = ''; }
}
$('choose-files').onclick = () => $('files').click();
$('choose-folder').onclick = () => $('folder').click();
for (const id of ['files','folder']) $(id).onchange = event => send(Array.from(event.target.files));
$('refresh').onclick = () => refresh().catch(error => status(error.message));
for (const kind of ['dragover','dragleave','drop']) $('drop').addEventListener(kind, event => {
  event.preventDefault(); $('drop').classList.toggle('drag', kind === 'dragover');
  if (kind === 'drop') {
    const items = Array.from(event.dataTransfer.items || []);
    if (items.some(item => item.webkitGetAsEntry?.()?.isDirectory)) { status('Use Choose folder to send a folder and preserve its structure.'); return; }
    send(Array.from(event.dataTransfer.files));
  }
});
refresh().catch(() => status('Cannot reach the computer. Check your Wi-Fi connection.'));
