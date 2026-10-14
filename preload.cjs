const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('snagfox',{action:(op,data={})=>ipcRenderer.invoke('action',op,data),on:(event,callback)=>{if(['state','stage','capture','focus-address','clipboard','find','paste-link','address-edit'].includes(event))ipcRenderer.on(event,(_,data)=>callback(data));}});
