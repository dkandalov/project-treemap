function xyzMouseController(target, env, speed, onUpdate) {
    speed = speed || {x: 0.5, y: 0.5};
    var mouseX = 0;
    var mouseY = 0;
    env.addRenderListener(function() {
        target.setX(target.getX() + (mouseX - target.getX() * speed.x));
        target.setY(target.getY() + (-mouseY - target.getY() * speed.y));
        if (onUpdate !== undefined) onUpdate();
    })
    return function(event) {
        mouseX = (event.clientX / env.window.innerWidth) * 2 - 1;
        mouseY = -(event.clientY / env.window.innerHeight) * 2 + 1;
    };
}

function xyzKeyController(target, stepSize, onUpdate) {
    if (stepSize === undefined) stepSize = 100;
    var dx, dy, dz;
    if (_.isArray(stepSize)) {
        dx = stepSize[0];
        dy = stepSize[1];
        dz = stepSize[2];
    } else {
        dx = stepSize;
        dy = stepSize;
        dz = stepSize;
    }
    if (onUpdate !== undefined) onUpdate();

    var j = 106, l = 108, k = 107, i = 105, u = 117, o = 111;
    return function (event) {
        var key = (event.key !== undefined ? event.key : event.keyCode);
        if (key === j) target.setX(target.getX() - dx);
        if (key === l) target.setX(target.getX() + dx);
        if (key === k) target.setY(target.getY() - dy);
        if (key === i) target.setY(target.getY() + dy);
        if (key === u) target.setZ(target.getZ() - dz);
        if (key === o) target.setZ(target.getZ() + dz);
        if (onUpdate !== undefined && _.contains([j, l, k, i, u, o], key)) {
            onUpdate();
        }
    }
}

function newPositionOnSphere(delegate) {
    var r = 1000;
    var theta = 0;
    var phi = 0;
    var center = new THREE.Vector3(0, 0, 0);
    var bounds = {x: undefined, y: undefined, z: undefined};

    function update() {
        if (bounds.x !== undefined) theta = THREE.Math.clamp(theta, bounds.x[0], bounds.x[1]);
        if (bounds.y !== undefined) phi = THREE.Math.clamp(phi, bounds.y[0], bounds.y[1]);
        if (bounds.z !== undefined) r = THREE.Math.clamp(r, bounds.z[0], bounds.z[1]);

        delegate.x = center.x + r * Math.cos(theta) * Math.sin(phi);
        delegate.y = center.y + r * Math.sin(theta) * Math.sin(phi);
        delegate.z = center.z + r * Math.cos(phi);
    }
    update();

    return {
        getX: function () { return theta; },
        getY: function () { return phi; },
        getZ: function () { return r; },
        set: function (x, y, z) {
            theta = x;
            phi = y;
            r = z;
            update();
            return this;
        },
        setX: function (value) {
            theta = value;
            update();
            return this;
        },
        setY: function (value) {
            phi = value;
            update();
            return this;
        },
        setZ: function (value) {
            r = value;
            update();
            return this;
        },
        center: function(x, y, z) {
            center = new THREE.Vector3(x, y, z);
            return this;
        },
        bounds: function (xRange, yRange, zRange) {
            bounds = {x: xRange, y: yRange, z: zRange};
            return this;
        }
    };
}

function showXYZPlanesOn(scene, planeSize, opacity, colors) {
    planeSize = planeSize || 5000;
    opacity = opacity || 0.2;
    colors = colors || [0x000000, 0xaa0000, 0x00aa00];
    colors = (_.isArray(colors) ? colors : [colors, colors, colors]);
    var planeGeometry = new THREE.PlaneBufferGeometry(planeSize, planeSize, 1, 1);

    var xyPlane = new THREE.Mesh(planeGeometry, new THREE.MeshBasicMaterial({color: colors[0], side: THREE.DoubleSide, transparent: true, opacity: opacity}));
    xyPlane.position.set(planeSize / 2, planeSize / 2, 0);
    xyPlane.rotation.set(0, 0, Math.PI / 2);
    scene.add(xyPlane);

    var xzPlane = new THREE.Mesh(planeGeometry, new THREE.MeshBasicMaterial({color: colors[1], side: THREE.DoubleSide, transparent: true, opacity: opacity}));
    xzPlane.position.set(planeSize / 2, 0, planeSize / 2);
    xzPlane.rotation.set(Math.PI / 2, 0, 0);
    scene.add(xzPlane);

    var yzPlane = new THREE.Mesh(planeGeometry, new THREE.MeshBasicMaterial({color: colors[2], side: THREE.DoubleSide, transparent: true, opacity: opacity}));
    yzPlane.position.set(0, planeSize / 2, planeSize / 2);
    yzPlane.rotation.set(0, Math.PI / 2, 0);
    scene.add(yzPlane);
}
