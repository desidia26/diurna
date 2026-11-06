import { mount } from 'svelte';
import App from './App.svelte';
import { createBridge } from './bridge';
const native = (window as any).diurna;
if (native) {
 const query = createBridge(native);
 mount(App, {target:document.getElementById('app')!, props:{query, failed:()=>{void query('client.failed').catch(()=>{});}}});
}
