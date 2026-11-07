"""Synthetic Pointer Events contract shared by every browser engine; CDP tests stay separate."""


def pointer_contract(page):
    result = page.locator("#ink-surface").evaluate("""async element => {
        // Untrusted PointerEvents cannot create OS contacts. Virtualize capture only for these
        // test IDs; actual browser mouse/Chromium pen/touch tests use the real capture methods.
        const ids = new Set([100001,100002,100003,100004,100005,100006]), captured = new Set();
        const original = {set:element.setPointerCapture, has:element.hasPointerCapture, release:element.releasePointerCapture};
        element.setPointerCapture = function(id) { if (ids.has(id)) captured.add(id); else original.set.call(this,id); };
        element.hasPointerCapture = function(id) { return ids.has(id) ? captured.has(id) : original.has.call(this,id); };
        element.releasePointerCapture = function(id) { if (ids.has(id)) captured.delete(id); else original.release.call(this,id); };
        const origin = performance.now(), rect = element.getBoundingClientRect();
        const make = (type,id,x,time,pressure=.5) => {
            const event = new PointerEvent(type, {pointerId:id, pointerType:'pen', button:0,
                buttons:type === 'pointerup' ? 0 : 1, clientX:rect.left+x/960*rect.width,
                clientY:rect.top+380/500*rect.height, pressure, tiltX:20, tiltY:30, bubbles:true});
            Object.defineProperty(event,'timeStamp',{value:origin+time});
            // Some engines leave constructor altitude/azimuth at zero despite tiltX/Y.
            Object.defineProperty(event,'altitudeAngle',{value:1});
            Object.defineProperty(event,'azimuthAngle',{value:.5});
            return event;
        };
        const send = (type,id,x,time,pressure) => element.dispatchEvent(make(type,id,x,time,pressure));
        const check = (condition,message) => { if (!condition) throw Error(message); };
        try {
            send('pointerdown',100001,240,0,.4);
            const history = [make('pointermove',100001,280,.25,.65),make('pointermove',100001,320,.5,.8)];
            const move = history[1];
            Object.defineProperty(move,'getCoalescedEvents',{value:()=>history});
            Object.defineProperty(move,'getPredictedEvents',{value:()=>[make('pointermove',100001,900,8,1)]});
            element.dispatchEvent(move);
            check(byteinkPredicted() === 1,'Prediction was not consumed');
            const replace = make('pointermove',100001,340,1,.85);
            Object.defineProperty(replace,'getCoalescedEvents',{value:()=>[]});
            Object.defineProperty(replace,'getPredictedEvents',{value:()=>[]});
            element.dispatchEvent(replace);
            check(byteinkPredicted() === 0,'Empty prediction did not retract the tail');
            send('pointerup',100001,360,1.25,0);
            const saved = byteinkLastInput;
            check(saved.count === 5,'Real coalesced observations were lost or prediction was saved');
            // Ink stores elapsed seconds as float32; the adapter must retain the sub-ms times.
            check(saved.samples.every((p,i)=>Math.abs(p[2]-[0,.25,.5,1,1.25][i]) < .0001),
                'Fractional times changed: '+saved.samples.map(p=>p[2]));
            check(saved.samples.every(p=>p[0] < 400),'Prediction entered storage');
            check(Math.abs(saved.samples[1][3]-.65) < .0001 && saved.samples[1][4] > 0 && saved.samples[1][5] > 0,
                'Pen axes changed: '+JSON.stringify(saved.samples[1]));
            send('pointerdown',100002,500,10);
            check(captured.has(100002),'Pointer was not captured');
            send('lostpointercapture',100002,500,11);
            check(byteinkActive() === 0 && !captured.has(100002),'Lost capture did not cancel');
            send('pointerdown',100003,500,20); send('pointerdown',100004,650,30);
            check(byteinkActive() === 2,'Concurrent gestures were not independent');
            send('pointerup',100003,520,21);
            check(byteinkActive() === 1,'Finishing one contact removed another');
            send('pointercancel',100004,650,31);
            check(byteinkActive() === 0,'Cancel left a live contact');
            send('pointerdown',100005,500,40); window.dispatchEvent(new Event('blur'));
            check(byteinkActive() === 0,'Blur left a live contact');
            send('pointerdown',100006,500,50);
            const height = element.style.height; element.style.height = (rect.height+1)+'px';
            await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
            check(byteinkActive() === 0,'Resize left a live contact'); element.style.height = height;
            await new Promise(resolve=>requestAnimationFrame(resolve));
            check(captured.size === 0,'Capture was retained after finish/cancel');
            check(document.getElementById('status').textContent.includes('2 strokes'),'Cancelled gestures entered storage');
            return {source:'synthetic-pointer-events',coalesced:2,fractionalTimes:true,penAxes:true,
                predictionReplacement:true,concurrentContacts:true,lostCapture:true,blur:true,resize:true,realSavedInputs:saved.count};
        } finally {
            for (const id of ids) send('pointercancel',id,0,60);
            element.setPointerCapture=original.set; element.hasPointerCapture=original.has; element.releasePointerCapture=original.release;
        }
    }""")
    page.click("#clear")
    return result
