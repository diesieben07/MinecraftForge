var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI')
var Opcodes = Java.type('org.objectweb.asm.Opcodes')
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode')
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode')

function initializeCoreMod() {
    var data = ASMAPI.loadData('coremods/constructor_to_factory.json')
    //ASMAPI.log('DEBUG', JSON.stringify(data, null, 2))
    
    var ret = {}
	for (var name in data) {
		addTargets(ret, name, data[name])
	}
    return ret
}

function addTargets(ret, name, data) {
    for (var x = 0; x < data.targets.length; x++) {
        var key = name + '.' + x
        var entry = data.targets[x]
        
        //ASMAPI.log('DEBUG', 'Entry ' + key + ' ' + JSON.stringify(entry))
        
        ret[key] = {
            'target': {
                'type': 'METHOD',
                'class': entry.owner,
                'methodName': entry.name,
                'methodDesc': entry.desc
            },
            'transformer': function(node) {
                return transform(node, data.cls, data.targetClass, data.targetMethod);
            }
        }
    }
}

function transform(node, cls, targetClass, targetMethod) {
    var hadNew = false
    for (var x = 0; x < node.instructions.size(); x++) {
        var current = node.instructions.get(x)
        if (current.getOpcode() == Opcodes.NEW && current.desc.equals(cls)) {
            hadNew = true
            var next = current.getNext();
            node.instructions.set(current, new InsnNode(Opcodes.NOP));
            node.instructions.set(next, new InsnNode(Opcodes.NOP));
        } else if (hadNew && current.getOpcode() == Opcodes.INVOKESPECIAL &&  current.owner.equals(cls) && current.name.equals("<init>")) {
            hadNew = false;
            var modifiedDescriptor = current.desc.substring(0, current.desc.length - 1) + "L" + cls + ";";
            node.instructions.set(current, new MethodInsnNode(Opcodes.INVOKESTATIC, targetClass, targetMethod, modifiedDescriptor, false));
        }
    }
    return node
}
