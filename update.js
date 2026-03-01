#!/usr/bin/env node
/**
 * Copies farming and hunter timer classes from RuneLite into this project.
 * Run: npm install && node update.js
 * Requires RuneLite cloned as a sibling: ../runelite/
 * Based on https://github.com/queicherius/runelite-time-tracking-reminder
 */
const fs = require('fs-extra')
const path = require('path')

const RUNELITE_PLUGIN_PATH = path.join(__dirname, '../runelite/runelite-client/src/main/java/net/runelite/client/plugins/timetracking')
const RUNELITE_COPY_PATH = path.join(__dirname, 'src/main/java/com/mystix/runelite')

// Exclude UI/panel classes we don't need for Mystix
const IGNORED_FILES = [
  'FarmingTabPanel.java',
  'FarmingNextTickPanel.java',
  'FarmingContractInfoBox.java',
  'FarmingContractManager.java',
  'BirdHouseTabPanel.java',
]

function patchCopiedFiles(search, replace) {
  const files = getDirectoryFiles(RUNELITE_COPY_PATH)
  for (const file of files) {
    let content = fs.readFileSync(file, 'utf-8')
    const newContent = typeof search === 'string' ? content.replace(search, replace) : content.replace(search, replace)
    if (newContent !== content) {
      fs.writeFileSync(file, newContent, 'utf-8')
    }
  }
}

function getDirectoryFiles(directory) {
  if (!fs.existsSync(directory)) return []
  const entries = fs.readdirSync(directory, { withFileTypes: true })
  const files = entries.map((entry) => {
    const entryPath = path.resolve(directory, entry.name)
    if (entry.isFile() && !entry.name.endsWith('.java')) return null
    return entry.isDirectory() ? getDirectoryFiles(entryPath) : entryPath
  })
  return files.filter(Boolean).flat()
}

if (!fs.existsSync(RUNELITE_PLUGIN_PATH)) {
  console.error('RuneLite not found. Clone it as a sibling:')
  console.error('  cd .. && git clone https://github.com/runelite/runelite.git')
  console.error('  cd mystix-plugin && node update.js')
  process.exit(1)
}

console.log('Creating RuneLite copy directories')
fs.mkdirpSync(path.join(RUNELITE_COPY_PATH, 'hunter'), { recursive: true })
fs.mkdirpSync(path.join(RUNELITE_COPY_PATH, 'farming'), { recursive: true })

console.log('Copying Tab.java')
fs.mkdirpSync(RUNELITE_COPY_PATH, { recursive: true })
fs.copySync(path.join(RUNELITE_PLUGIN_PATH, 'Tab.java'), path.join(RUNELITE_COPY_PATH, 'Tab.java'))

console.log('Copying farming and hunter files')
const copyOptions = {
  overwrite: true,
  filter: (src) => !IGNORED_FILES.includes(path.basename(src)),
}
fs.copySync(path.join(RUNELITE_PLUGIN_PATH, 'hunter'), path.join(RUNELITE_COPY_PATH, 'hunter'), copyOptions)
fs.copySync(path.join(RUNELITE_PLUGIN_PATH, 'farming'), path.join(RUNELITE_COPY_PATH, 'farming'), copyOptions)

console.log('Patching: Change package to com.mystix.runelite')
patchCopiedFiles(
  'package net.runelite.client.plugins.timetracking.',
  'package com.mystix.runelite.'
)
patchCopiedFiles(
  'package net.runelite.client.plugins.timetracking;',
  'package com.mystix.runelite;'
)
patchCopiedFiles('import net.runelite.client.plugins.timetracking.Tab;', 'import com.mystix.runelite.Tab;')

console.log('Patching: Remove @Inject and @Singleton')
patchCopiedFiles(/\s*@Inject\n/g, '\n')
patchCopiedFiles(/\s*@Singleton\n/g, '\n')

console.log('Patching: Make classes and constructors public')
patchCopiedFiles(/access = AccessLevel\.PACKAGE,?/g, '')
patchCopiedFiles(/access = AccessLevel\.PRIVATE,?/g, '')
patchCopiedFiles('private BirdHouseTracker(', 'public BirdHouseTracker(')
patchCopiedFiles('private void updateCompletionTime', 'public void updateCompletionTime')
patchCopiedFiles('private FarmingTracker(', 'public FarmingTracker(')
patchCopiedFiles(/\bclass FarmingWorld\b/, 'public class FarmingWorld')
patchCopiedFiles(/\tFarmingWorld\(/, '\tpublic FarmingWorld(')
patchCopiedFiles(/\bclass FarmingPatch\b/, 'public class FarmingPatch')
patchCopiedFiles(/\bclass PatchPrediction\b/, 'public class PatchPrediction')
patchCopiedFiles(/\tFarmingRegion\(/, '\tpublic FarmingRegion(')

console.log('Patching: Stub UI methods (BirdHouseTabPanel/FarmingTabPanel not copied)')
patchCopiedFiles('public BirdHouseTabPanel createBirdHouseTabPanel()', 'public Object createBirdHouseTabPanel()')
patchCopiedFiles('return new BirdHouseTabPanel(configManager, itemManager, this, config);', 'return null;')
patchCopiedFiles('public FarmingTabPanel createTabPanel(Tab tab, FarmingContractManager farmingContractManager)', 'public Object createTabPanel(Tab tab, Object farmingContractManager)')
patchCopiedFiles('return new FarmingTabPanel(this, compostTracker, paymentTracker, itemManager, configManager, config, farmingWorld.getTabs().get(tab), farmingContractManager);', 'return null;')

console.log('Patching: Remove config write calls (read-only for Mystix)')
patchCopiedFiles(
  /configManager\.setRSProfileConfiguration\([^)]+\);/g,
  '/* config write removed - Mystix is read-only */'
)

console.log('Patching: Add getEntityIdForCompletionTime to BirdHouseTracker')
const birdHouseTrackerPath = path.join(RUNELITE_COPY_PATH, 'hunter', 'BirdHouseTracker.java')
if (fs.existsSync(birdHouseTrackerPath)) {
  let content = fs.readFileSync(birdHouseTrackerPath, 'utf-8')
  const method = `
	/**
	 * Returns the OSRS item ID of a bird house that completes at the given time, or null if not found.
	 * Used by Mystix for entity_id in timer sync.
	 */
	public Integer getEntityIdForCompletionTime(long completionTime) {
		for (BirdHouseData data : birdHouseData.values()) {
			if (BirdHouseState.fromVarpValue(data.getVarp()) == BirdHouseState.SEEDED
				&& data.getTimestamp() + BIRD_HOUSE_DURATION == completionTime) {
				BirdHouse birdHouse = BirdHouse.fromVarpValue(data.getVarp());
				if (birdHouse != null) {
					return birdHouse.getItemID();
				}
			}
		}
		return null;
	}
`
  content = content.replace(/\n}$/, method + '\n}')
  fs.writeFileSync(birdHouseTrackerPath, content, 'utf-8')
}

console.log('Patching: Add generation comment')
const GENERATED_COMMENT = '// Auto-generated from RuneLite. Do not edit. Run: node update.js\n\n'
patchCopiedFiles(/\/\*\s*\n\s*\* Copyright/, GENERATED_COMMENT + '/*\n * Copyright')

// Handle duplicate comment
const files = getDirectoryFiles(RUNELITE_COPY_PATH)
for (const file of files) {
  let content = fs.readFileSync(file, 'utf-8')
  content = content.replace(GENERATED_COMMENT + GENERATED_COMMENT, GENERATED_COMMENT)
  fs.writeFileSync(file, content, 'utf-8')
}

console.log('Done. Run ./gradlew build to verify.')
