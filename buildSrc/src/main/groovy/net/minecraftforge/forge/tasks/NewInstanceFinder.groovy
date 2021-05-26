package net.minecraftforge.forge.tasks

import groovy.transform.EqualsAndHashCode
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

import static org.objectweb.asm.Opcodes.*

public class NewInstanceFinder extends BytecodeFinder {
    @Nested
    Map<String, Search> instances = [:] as HashMap
    Map<Search, String> instancesReverse = [:] as HashMap
    Map<String, Set<ObjectTarget>> targets = [:] as TreeMap
    
    @Override
    protected pre() {
        //fields.each{ k,v -> logger.lifecycle("Fields: " + k + ' ' + v) }
    }
    
    @Override
    protected process(ClassNode parent, MethodNode node) {
        def last = null
        def parentInstance = new ObjectTarget(owner: parent.name, name: '', desc: '')
        for (int x = 0; x < node.instructions.size(); x++) {
            def current = node.instructions.get(x)
            if (current.opcode == NEW) {
                def target = new Search(cls: current.desc)
                def wanted = instancesReverse.get(target)
                def original = instances.get(wanted)
                def instance = new ObjectTarget(owner: parent.name, name: node.name, desc: node.desc)
                if (wanted != null && (original.blacklist == null || (!original.blacklist.contains(instance) && !original.blacklist.contains(parentInstance)))) {
                    targets.computeIfAbsent(wanted, { k -> new TreeSet() }).add(instance)
                }
            }
            last = current
        }
    }
    
    @Override
    protected Object getData() {
		def ret = [:] as HashMap
		targets.forEach{ k, v -> 
			def e = instances.get(k)
			ret[k] = [
				cls: e.cls, 
				targetClass: e.targetClass,
                targetMethod: e.targetMethod,
				targets: v
			]
		}
        return ret
    }
    
    @EqualsAndHashCode(excludes = ['targetClass', 'targetMethod', 'blacklist'])
    public static class Search {
        @Input
        String cls
        
        @Input
        String targetClass

		@Input
		String targetMethod

        @Nested
        @Optional
        Set<ObjectTarget> blacklist
        
        @Override
        String toString() {
            return cls + '.' + name
        }
        
        def blacklist(def owner, def name, def desc) {
            if (blacklist == null)
                blacklist = new HashSet()
            blacklist.add(new ObjectTarget(owner: owner, name: name, desc: desc))
        }
        def blacklist(def owner) {
            blacklist(owner, '', '')
        }
    }
    
    def classes(Closure cl) {
        new ClosureHelper(cl, {name, ccl ->
            def search = ClosureHelper.apply(new Search(), ccl)
            this.instances.put(name, search)
            this.instancesReverse.put(search, name)
        })
    }
}
