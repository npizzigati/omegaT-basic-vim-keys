/* :name=Basic Vim Keys :description=Basic Vim keys for OmegaT editor pane
 *
 * @author: Nick Pizzigati
 */


import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.FontMetrics;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;
import javax.swing.text.BadLocationException;

import org.omegat.gui.editor.IEditor;
import org.omegat.gui.editor.EditorTextArea3;
import org.omegat.gui.editor.EditorController;
import org.omegat.gui.editor.EditorSettings;
import org.omegat.util.Java8Compat;
import org.omegat.util.gui.Styles;

class InterruptException extends Exception {
  InterruptException(){
    super();
  }

  InterruptException(String message){
    super(message);
  }
}

enum ModeID {
  NORMAL, INSERT, VISUAL, OPERATOR_PENDING;
}

enum SubModeID {
  TO_OR_TILL, SNEAK;
}

enum Operator {
  DELETE, CHANGE, YANK, NONE;
}

class Listener implements KeyListener {
  // char keyChar;
  EditorTextArea3 pane;
  EditorSettings editorSettings;
  KeyEvent lastKeyPressed;
  KeyEvent lastKeyTyped;
  KeyEvent lastConsumedKeyPressed;
  KeyEvent lastConsumedKeyTyped;
  KeyManager keyManager;
  Stroke stroke;
  
  static final FAKE_REDISPATCH_DELAY = 50;

  Listener(EditorController editor,
           EditorTextArea3 pane) {
    this.pane = pane;
    lastKeyPressed = null;
    lastKeyTyped = null;
    keyManager = new KeyManager(editor, pane);
    editorSettings = editor.getSettings();
    startListening();
  }

  void startListening() {
    pane.addKeyListener(this);
    println 'Listening now';
  }

  void stopListening () {
    pane.removeKeyListener(this);
  }

  void storeLastConsumed() {
    lastConsumedKeyPressed = lastKeyPressed;
    lastConsumedKeyTyped = lastKeyTyped;
  }

  void resetKeyEvents() {
    lastKeyPressed = null
    lastKeyTyped = null
  }

  // OmegaT consumes the KeyPressed event when the
  // "Advance on tab" option is selected, but check for it
  // below (isIgnoredTab) in case that implementation is changed
  // in the future
  void keyPressed(KeyEvent event) {
    if(isRedispatchedEvent(event) || event.isActionKey() || isIgnoredTab(event)) {
      return; //This will allow event to pass on to pane
    }

    lastKeyPressed = event;

    if(lastKeyTyped) {
      stroke = new Stroke(lastKeyPressed, lastKeyTyped);
      storeLastConsumed();
      resetKeyEvents();
      try {
        keyManager.route(stroke);
      } catch (InterruptException e) {
        stopListening();
        // Set caret back to normal
        CaretUtilities.resetCaret(pane);
        println e.message;
      }
    }
    event.consume();
  }

  void keyTyped(KeyEvent event) {
    if(isRedispatchedEvent(event) || isIgnoredTab(event)) {
      return;
    }

    lastKeyTyped = event;

    // If remap dispatch underway, there will be no keyPressed
    // event. It also appears as though OmegaT is capturing the
    // keyPressed event for the enter key, but not the keyTyped
    // event (i.e., as far as this script is concerned, the enter
    // key only produces a keyTyped event; in any case, even if a
    // keyPressed event is generated for the enter key, this
    // should still work)
    if(lastKeyPressed || keyManager.isRemapDispatchUnderway || isEnterKey(event)) {
      stroke = new Stroke(lastKeyPressed, lastKeyTyped);
      storeLastConsumed();
      resetKeyEvents();
      try {
        keyManager.route(stroke);
      } catch (InterruptException e) {
        stopListening();
        println e.message;
      }
    }
    event.consume();

    // // Hacky fix to cursor disappearing
    // SwingUtilities.invokeLater(new Runnable() {
    //   public void run() {
    //     // pane.revalidate();
    //   }
    // });

  }

  boolean isEnterKey(event) {
    ((int)(event.getKeyChar()) == 10);
  }

  void keyReleased(KeyEvent releasedEvent) {
    releasedEvent.consume();
  }

  boolean isRedispatchedEvent(KeyEvent event) {
    KeyEvent lastConsumedEvent = (event.getID() == KeyEvent.KEY_PRESSED) ? lastConsumedKeyPressed
                                                                         : lastConsumedKeyTyped;
    (lastConsumedEvent != null) && isSameEvent(event, lastConsumedEvent);
  }

  boolean isIgnoredTab(KeyEvent event) {
    ((int)event.getKeyChar() == 9) && editorSettings.isUseTabForAdvance()
  } 

  boolean isSameEvent(event, lastConsumedEvent) {
    // Redispatched event is set to a fraction of a second earlier than
    // original event to avoid repeating keys (like when holding
    // key down) from being considered redispatched events
    (lastConsumedEvent.getWhen() == (event.getWhen() + FAKE_REDISPATCH_DELAY)
     && (lastConsumedEvent.getKeyChar() == event.getKeyChar()));
  }
}

class Stroke {
  KeyEvent keyPressed;
  KeyEvent keyTyped;

  Stroke(keyPressed, keyTyped) {
    this.keyPressed = keyPressed;
    this.keyTyped = keyTyped;
  }
}

abstract class Mode {
  KeyManager keyManager;
  ActionManager actionManager;
  // userEnteredRemaps is temporary -- these will be retrieved
  // from user or configuration file
  Map userEnteredRemaps;
  Map remaps;
  Map remapCandidates;
  Map accumulatedStrokes;
  List remapDispatchQueue;
  List strokeDispatchQueue;
  Long lastKeypressTime;
  Thread remapTimeoutThread;
  static final VK_KEYS = ['<esc>': KeyEvent.VK_ESCAPE, '<bs>': KeyEvent.VK_BACK_SPACE];

  Mode(KeyManager keyManager, ActionManager actionManager) {
    this.keyManager = keyManager;
    this.actionManager = actionManager;
    resetAccumulatedStrokes();
    resetRemapCandidates();
    remapDispatchQueue = [];
    strokeDispatchQueue = [];
    userEnteredRemaps = [:];
    remaps = tokenizeUserEnteredRemaps();
  }

  class RemapTimeoutThread extends Thread {
    long cutoff = lastKeypressTime + REMAP_TIMEOUT;

    public void run() {
      while (System.currentTimeMillis() < cutoff) {
        if (Thread.interrupted()) return;
      }

      strokeDispatchQueue = accumulatedStrokes.values() as List;
      invokeLaterDispatch(strokeDispatchQueue.clone());
      resetAccumulatedStrokes();
    }
  }

  void process(Stroke stroke) {
    if (strokeDispatchQueue) {
      execute(stroke);
      strokeDispatchQueue.pop();
      return;
    }

    if (isRemapTimeoutThreadRunning())
      remapTimeoutThread.interrupt();

    int key = stroke.keyTyped.getKeyChar();

    accumulatedStrokes << [(key): stroke];
    remapCandidates = findRemapCandidates();

    if (remapCandidates) {
      handlePossibleRemapMatch();
    } else {
      refireNonRemappedStrokes();
    }
  }

  void startRemapTimeoutThread() {
    lastKeypressTime = System.currentTimeMillis();
    remapTimeoutThread = new RemapTimeoutThread();
    remapTimeoutThread.start();
  }

  void invokeLaterDispatch(List queue) {
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        keyManager.batchRedispatchStrokes(queue);
      }
    });
  }

  void handlePossibleRemapMatch() {
      startRemapTimeoutThread();

      def remapMatch = remaps[accumulatedStrokes.keySet() as List];
      if (remapMatch) {
        resetAccumulatedStrokes();
        resetRemapCandidates();
        remapTimeoutThread.interrupt();
        keyManager.dispatchRemapMatchKeys(remapMatch.clone());
      }
  }

  List tokenizeRemapString(String remapString) {
    Pattern pattern = Pattern.compile("(<\\w+>|\\w)");
    Matcher matcher = pattern.matcher(remapString);
    List<String> results = [];
    while (matcher.find()) {
      results << translateKey(matcher.group(1));
    }
    return results;
  }

  Map tokenizeUserEnteredRemaps() {
    Map tokenizedRemaps = [:];
    userEnteredRemaps.each { k, v ->
      tokenizedRemaps[tokenizeRemapString(k)] = tokenizeRemapString(v);
    }
    return tokenizedRemaps;
  }

  int translateKey(key) {
    return VK_KEYS[key.toLowerCase()] ?: (int)key;
  }

  void refireNonRemappedStrokes() {
    strokeDispatchQueue = (strokeDispatchQueue << accumulatedStrokes.values()).flatten();
    keyManager.batchRedispatchStrokes(strokeDispatchQueue.clone());
    resetAccumulatedStrokes();
  }

  void resetRemapCandidates() {
    remapCandidates = [:];
  }

  void resetAccumulatedStrokes() {
    accumulatedStrokes = [:];
  }

  Map findRemapCandidates() {
    remaps.findAll { k, v ->
      int candidate_size = accumulatedStrokes.size();
      int end_idx = candidate_size - 1;
      k[0..end_idx] == accumulatedStrokes.keySet() as List;
    }
  }

  boolean isRemapTimeoutThreadRunning() {
    remapTimeoutThread && remapTimeoutThread.isAlive()
  }

  boolean isNewLineBackspaceOrEscape(int keyChar) {
    // If key isn't actionable key (e.g., it's a newline,
    // backspace or escape), return true
    [8, 10, 27].contains(keyChar)
  }

  boolean isDelete(int key) {
    key == 127;
  }

  boolean isNewline(int key) {
    key == 10;
  }

  boolean isBackspace(int key) {
    key == 8;
  }

  boolean isEscape(int key) {
    key == 27;
  }

  abstract void execute(Stroke stroke);
}

class NormalMode extends Mode {
  static final int REMAP_TIMEOUT = 1000;
  int keyChar;

  NormalMode(KeyManager keyManager, ActionManager actionManager) {
    super(keyManager, actionManager);
    userEnteredRemaps = [:];
    remaps = tokenizeUserEnteredRemaps();
  }

  void execute(Stroke stroke) {
    keyChar = (int)stroke.keyTyped.getKeyChar();

    if ([(int)'f', (int)'F', (int)'t', (int)'T'].contains(keyChar)) {
      keyManager.setSubMode(SubModeID.TO_OR_TILL);
      actionManager.processActionableKey(keyChar);
    } else if (isEscape(keyChar)) {
      actionManager.resetActionableKeys();
    } else if (isDelete(keyChar)) {
      keyManager.redispatchStrokeToPane(stroke);
    } else if (isBackspace(keyChar)) {
      actionManager.processActionableKey((int)'h')
    } else if ([(int)'s', (int)'S'].contains(keyChar)) {
      keyManager.setSubMode(SubModeID.SNEAK);
      actionManager.processActionableKey(keyChar);
    } else if (keyChar == (int)'i') {
      keyManager.switchTo(ModeID.INSERT);
    } else if (keyChar == (int)'a') {
      actionManager.processActionableKey(keyChar);
      keyManager.switchTo(ModeID.INSERT);
    } else if (keyChar == (int)'d') {
      keyManager.switchTo(ModeID.OPERATOR_PENDING,
                          Operator.DELETE);
    } else if (keyChar == (int)'c') {
      keyManager.switchTo(ModeID.OPERATOR_PENDING,
                          Operator.CHANGE);
    } else if (keyChar == (int)'y') {
      keyManager.switchTo(ModeID.OPERATOR_PENDING,
                          Operator.YANK);
    } else if ((char)keyChar =~ /[\dbewlhPpftsSxDC$]/) {
      actionManager.processActionableKey(keyChar);
    }
  }
}

class OperatorPendingMode extends Mode {
  static final int REMAP_TIMEOUT = 900; // In milliseconds
  // Operator operator;
  int keyChar;

  OperatorPendingMode(KeyManager keyManager, ActionManager actionManager) {
    super(keyManager, actionManager);
    userEnteredRemaps = [:];
    remaps = tokenizeUserEnteredRemaps();
  }

  void execute(Stroke stroke) {
    keyChar = (int)stroke.keyTyped.getKeyChar();

    println("keyChar: $keyChar")
    if ([(int)'f', (int)'F', (int)'t', (int)'T'].contains(keyChar)) {
      keyManager.setSubMode(SubModeID.TO_OR_TILL);
      actionManager.processActionableKey(keyChar);
    } else if (isBackspace(keyChar)) {
      actionManager.processActionableKey((int)'h')
    } else if ([(int)'s', (int)'S'].contains(keyChar)) {
      keyManager.setSubMode(SubModeID.SNEAK);
      actionManager.processActionableKey(keyChar);
    } else if ((char)keyChar =~ /[\ddwlhPpftsSx$]/) {
      println "action key"
      actionManager.processActionableKey(keyChar);
    } else {
      keyManager.switchTo(ModeID.NORMAL);
    }
  }
}

class InsertMode extends Mode {
  static final int REMAP_TIMEOUT = 20; // In milliseconds

  InsertMode(KeyManager keyManager, ActionManager actionManager) {
    super(keyManager, actionManager);
    userEnteredRemaps = ['ei': '<Esc>', 'ie': '<Esc>'];
    remaps = tokenizeUserEnteredRemaps();
  }

  void execute(Stroke stroke) {
    int keyChar = (int)stroke.keyTyped.getKeyChar();

    if (keyChar == KeyEvent.VK_ESCAPE) {
      keyManager.switchTo(ModeID.NORMAL)
    } else if (!isNewline(keyChar)) {
      keyManager.redispatchStrokeToPane(stroke);
    }
  }
}

class VisualMode extends Mode {
  VisualMode(KeyManager keyManager, ActionManager actionManager) {
    super(keyManager, actionManager);
    userEnteredRemaps = [:];
    remaps = tokenizeUserEnteredRemaps();
  }

  void execute(Stroke stroke) {
    // need to handle delete and backspace, too
    switch (keyChar) {
      case 'i':
        keyManager.switchTo(ModeID.INSERT);
        break;
      case 'h':
        keyManager.moveCaret(-1)
        break;
      case 'l':
        keyManager.moveCaret(1)
        break;
    }
  }
}

class ToOrTillSubMode extends Mode {
  
  ToOrTillSubMode(KeyManager keyManager, ActionManager actionManager) {
    super(keyManager, actionManager);
  }

  void execute(Stroke stroke) {
    int keyChar = (int)stroke.keyTyped.getKeyChar();
    if (isNewLineBackspaceOrEscape(keyChar)) {
      cleanUpAndExit();
    }

    actionManager.processActionableKey(keyChar);
    cleanUpAndExit();
  }

  void cleanUpAndExit() {
    actionManager.resetActionableKeys();
    keyManager.exitSubMode();
  }
}

class SneakSubMode extends Mode {
  int charCount
  
  SneakSubMode(KeyManager keyManager, ActionManager actionManager) {
    super(keyManager, actionManager);
    charCount = 0;
  }

  void execute(Stroke stroke) {
    int keyChar = (int)stroke.keyTyped.getKeyChar();
    charCount += 1;

    if (isNewLineBackspaceOrEscape(keyChar)) {
      cleanUpAndExit();
    } else {
      actionManager.processActionableKey(keyChar);
    }

    if (charCount == 2) {
      cleanUpAndExit();
    }
  }

  void cleanUpAndExit() {
    charCount = 0;
    actionManager.resetActionableKeys();
    keyManager.exitSubMode();
  }
}

class KeyManager {
  Mode normalMode;
  Mode insertMode;
  Mode visualMode;
  Mode operatorPendingMode;
  Mode currentMode;
  Mode currentSubMode;
  Mode toOrTillSubMode;
  Mode sneakSubMode;
  Operator operator;
  ActionManager actionManager;
  boolean isRemapDispatchUnderway;
  EditorController editor;
  EditorTextArea3 pane;

  KeyManager(EditorController editor, EditorTextArea3 editorPane) {
    this.editor = editor;
    pane = editorPane;
    actionManager = new ActionManager(this, editor);
    normalMode = new NormalMode(this, actionManager);
    insertMode = new InsertMode(this, actionManager);
    visualMode = new VisualMode(this, actionManager);
    toOrTillSubMode = new ToOrTillSubMode(this, actionManager);
    sneakSubMode = new SneakSubMode(this, actionManager);
    operatorPendingMode = new OperatorPendingMode(this, actionManager);
    currentMode = normalMode;
    currentSubMode = null;
  }

  void switchTo(ModeID modeID, Operator operator) {
    if (modeID != ModeID.OPERATOR_PENDING) {
      return;
    }

    println('Switching to operator pending mode')
    currentMode = operatorPendingMode;
    this.operator = operator;
  }

  void switchTo(ModeID modeID) {
    // If swiching from operator pending mode, reset operator
    // to none
    if (currentMode == operatorPendingMode) {
        operator = Operator.NONE;
    }

    switch(modeID) {
      case ModeID.NORMAL:
        println('Switching to normal mode')
        currentMode = normalMode;
        executeCaretSwitch(modeID);
        break;
      case ModeID.INSERT:
        currentMode = insertMode;
        println('Switching to insert mode')
        executeCaretSwitch(modeID);
        break;
      case ModeID.VISUAL:
        currentMode = visualMode;
        println('Switching to visual mode')
        break;
    }
  }

  void setSubMode(SubModeID subModeID) {
    switch(subModeID) {
      case SubModeID.TO_OR_TILL:
        println('Switching to to or till submode')
        currentSubMode = toOrTillSubMode;
        break;
      case SubModeID.SNEAK:
        println('Switching to sneak submode')
        currentSubMode = sneakSubMode;
        break;
    }
  }

  void exitSubMode() {
    currentSubMode = null;
  }

  void executeCaretSwitch(ModeID modeID) {
    // Only switch caret when using ShapeShiftingCaret (e.g. not
    // in testing)
    if (pane.getCaret().class.name == 'ShapeShiftingCaret') {
      CaretUtilities.switchCaretShape(pane, modeID);
      pane.repaint();
      pane.revalidate();
    }
  }

  ModeID getCurrentModeID() {
    switch(currentMode) {
      case normalMode:
        return ModeID.NORMAL;
      case insertMode:
        return ModeID.INSERT;
      case visualMode:
        return ModeID.VISUAL;
      case operatorPendingMode:
        return ModeID.OPERATOR_PENDING;
    }
  }

  void route(Stroke stroke) throws InterruptException {
    int key = stroke.keyTyped.getKeyChar();

    // Exit on ctrl-Q
    // Need to check if stroke.KeyPressed is not null first
    // because events dispatched from a remap are only KeyTyped
    // events
    if (stroke.keyPressed &&
        (char)stroke.keyPressed.getKeyCode() == 'Q' &&
        ctrlPressed(stroke)) {
      throw new InterruptException();
    }

    // Immediately redispatch all keys with ctrl modifier
    // May want to change this to be able to use ctrl key in vim
    if (ctrlPressed(stroke)) {
      redispatchStrokeToPane(stroke);
    } else if (currentSubMode == null) {
      currentMode.process(stroke);
    } else {
      currentSubMode.process(stroke);
    }
  }

  void setOperator(Operator operator) {
    this.operator = operator 
  }

  Operator getOperator() {
    operator
  }

  boolean ctrlPressed(Stroke stroke) {
    if (!!(stroke.keyPressed)) {
      return !!(stroke.keyPressed.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK);
    }
  }

  void batchRedispatchStrokes(List queue) {
    queue.each {
      // For some reason, keyPressed events are not dispatched for
      // delete and escape if I don't create new event

      // If remap dispatch underway, there will be no keyPressed
      // so no KeyPressed to redispatch
      redispatchStrokeForProcessing(it);
      // if (it.keyPressed) {
      //   redispatchEventForProcessing(it.keyPressed);
      // }
      // redispatchEventForProcessing(it.keyTyped);
    }
  }

  void redispatchStrokeForProcessing(Stroke stroke) {
    if (stroke.keyPressed) {
      redispatchEventForProcessing(stroke.keyPressed);
    }
    redispatchEventForProcessing(stroke.keyTyped);
  }

  void dispatchRemapMatchKeys(List remapMatch) {
    isRemapDispatchUnderway = true;
    remapMatch.each {
      KeyEvent keyTypedEvent = createKeyTypedEvent(it);
      pane.dispatchEvent(keyTypedEvent);
      Thread.sleep(10); // Is this necessary? Prevent KeyEvents being created with the same time
    }
    isRemapDispatchUnderway = false;
  }
  
  KeyEvent createKeyTypedEvent(int key) { // this creates a typed event from a key
    int id = KeyEvent.KEY_TYPED;
    int keyCode = 0;
    char keyChar = key;
    long when = System.currentTimeMillis();
    int modifiers = 0;
    return new KeyEvent(pane, id, when, modifiers,
                        keyCode, keyChar);
  }

  void redispatchEventToPane(KeyEvent event) {
    // Redispatched event is set to a fraction of a second earlier than
    // original event to avoid repeating keys (like when holding
    // key down) from being considered redispatched events
    pane.dispatchEvent(new KeyEvent(pane, event.getID(),
                                    event.getWhen() - Listener.FAKE_REDISPATCH_DELAY,
                                    event.getModifiers(),
                                    event.getKeyCode(),
                                    event.getKeyChar()));
  }

  
  void redispatchEventForProcessing(KeyEvent event) {
    pane.dispatchEvent(new KeyEvent(pane, event.getID(),
                                    event.getWhen(),
                                    event.getModifiers(),
                                    event.getKeyCode(),
                                    event.getKeyChar()));
  }

  void redispatchStrokeToPane(Stroke stroke) {
    redispatchEventToPane(stroke.getKeyPressed());
    redispatchEventToPane(stroke.getKeyTyped());
  }

}

class ActionManager {
  String actionableKeys;
  KeyManager keyManager;
  EditorController editor;
  Register register;

  ActionManager(KeyManager keyManager, EditorController editor) {
    this.keyManager = keyManager;
    this.editor = editor;
    this.register = new Register();
  }

  void processActionableKey(int keyChar) {
    String actionableKey = (char)keyChar;
    actionableKeys = (actionableKeys != null) ? actionableKeys += actionableKey : actionableKey;
    String nonCountKeys = removeCountKeys(actionableKeys);
    println "actionable keys: $actionableKeys"

    Map actions = [(/^w$/):    { cnt -> toBeginningOfWordAhead(cnt) },
                   (/^e$/):    { cnt -> toEndOfWordAhead(cnt) },
                   (/^a$/):    { moveCaret(1) },
                   (/^l$/):    { cnt -> moveCaret(cnt) },
                   (/^h$/):    { cnt -> moveCaret(-cnt) },
                   (/^P$/):    { cnt -> registerInsertBefore(cnt) },
                   (/^p$/):    { cnt -> registerInsertAfter(cnt) },
                   (/^0$/):    { moveToLineStart() },
                   (/^\$$/):   { moveToLineEnd() },
                   (/^f.$/):   { cnt, key -> goForwardToChar(cnt, key) },
                   (/^F.$/):   { cnt, key -> goBackwardToChar(cnt, key) },
                   (/^t.$/):   { cnt, key -> goForwardToChar(cnt, key) },
                   (/^s..$/):  { keys -> sneakForwardToChars(keys) },
                   (/^S..$/):  { keys -> sneakBackToChars(keys) },
                   (/^d$/):    { deleteLine() },
                   (/^b$/):    { cnt -> toBeginningOfWordBehind(cnt) },
                   (/^D$/):    { deleteToLineEnd() },
                   (/^C$/):    { changeToLineEnd() },
                   (/^x$/):    { cnt -> deleteChars(cnt) }]

    String match = actionMatch(actions, nonCountKeys);
    if (match) {
      int count = calculateCount(actionableKeys);
      println "count: $count"
      trigger(actions[match], count, nonCountKeys);
      actionableKeys = ''
    } 
  }

  String removeCountKeys(String actionableKeys) {
    // Do not remove numbers if action is sneak or toOrTill
    if (actionableKeys[0] =~ /[sSfFtT]/) {
      return actionableKeys
    }
    return actionableKeys.replaceAll(/[1-9]|(?<=[0-9])0/, '')
  }

  int calculateCount(actionableKeys) {
    int count = 1
    def matcher = actionableKeys =~ /(?<![fFtT])[0-9]+/
    int numberOfMatches = matcher.size()
    if (numberOfMatches == 1 && matcher[0] != '0')  {
      count = matcher[0].toInteger();
    } else if (numberOfMatches == 2) {
      count = matcher[0].toInteger() * matcher[1].toInteger();
    }
    return count
  }

  def actionMatch(Map actions, String nonCountKeys) {
    actions.keySet().find { mapKey -> nonCountKeys =~ mapKey }
  }

  void trigger(Closure action, int count, String nonCountKeys) {
    // Sneak
    if (nonCountKeys.length() == 3) {
      String targetKeys = nonCountKeys[1..-1];
      action.call(targetKeys);
    } else {
      String targetKey = (nonCountKeys =~ /[tfTF]/) ? nonCountKeys[-1]
                                                  : null
      (targetKey) ? action.call(count, targetKey) : action.call(count);
    }
    resetToNormalModeIfInOperatorPendingMode();
  }

  void resetToNormalModeIfInOperatorPendingMode() {
    if (keyManager.getCurrentModeID() == ModeID.OPERATOR_PENDING) {
      keyManager.switchTo(ModeID.NORMAL)
    }
  }

  void resetActionableKeys() {
    actionableKeys = '';
  }
  
  void testSelection() {
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    int positionChange = 2;
    IEditor.CaretPosition caret = new IEditor.CaretPosition(currentPos,
                                            currentPos + positionChange);
    editor.setCaretPosition(caret);
  }

  int getLength() {
    String text = editor.getCurrentTranslation();
    int length = text.length();
  }

  void registerInsertBefore(int count) {
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    String pullContent = register.getContent();
    editor.insertText(pullContent);
  }

  void registerInsertAfter(int count) {
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    placeCaret(currentPos + 1);
    String pullContent = register.getContent();
    editor.insertText(pullContent);
  }

  void deleteChars(int number) {
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    int length = getLength();

    int deleteStart = currentPos;
    int deleteEnd = currentPos + number;

    if (deleteEnd > length) {
      deleteEnd = length
    }

    editor.replacePartOfText('', deleteStart, deleteEnd);
  }

  void deleteLine() {
    int length = getLength();
    editor.replacePartOfText('', 0, length);
  }

  void deleteToPos(int currentPos, int newPos) {
    editor.replacePartOfText('', currentPos, newPos);
  }
  
  void changeToPos(int currentPos, int newPos) {
    deleteToPos(currentPos, newPos)
    keyManager.switchTo(ModeID.INSERT)
  }

  void selectToPos(int currentPos, int newPos) {
    IEditor.CaretPosition caretPosition = new IEditor.CaretPosition(currentPos,
                                                                    newPos);
    editor.setCaretPosition(caretPosition);
  }

  void placeCaret(int newPos) {
    IEditor.CaretPosition caretPosition = new IEditor.CaretPosition(newPos);
    editor.setCaretPosition(caretPosition);
  }

  void toEndOfWordAhead(int number) {
    // This is almost exactly like toBeginning... Can I extract
    // to a method?
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    String text = editor.getCurrentTranslation();
    int length = text.length();

    Pattern pattern = ~/([\p{L}\d](?=[^\p{L}\d]))|([^\p{L}\d\s])(?=[\p{L}\d\s])|(.$)/
    List matches = getMatches(text, pattern);

    List candidates = matches.findAll { it > currentPos };
    int endIndex = (currentPos == length - 1) ? length - 1 : length
    int newPos = (candidates[number - 1]) ?: endIndex;

    executeGoForwardToOperation(currentPos, newPos, text)
  }
  
  void toBeginningOfWordBehind(int number) {
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    String text = editor.getCurrentTranslation();
    int length = text.length();

    Pattern pattern = ~/((?<=[^\p{L}\d])[\p{L}\d])|(?<=[\p{L}\d\s])([^\p{L}\d\s])/
    List matches = getMatches(text, pattern);

    List candidates = matches.findAll { it < currentPos };
    int newPos = (candidates.size() >= number) ? (candidates[-number]) : 0;

    executeGoBackToOperation(currentPos, newPos, text)
  }

  void toBeginningOfWordAhead(int number) {
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    String text = editor.getCurrentTranslation();
    int length = text.length();

    Pattern pattern = ~/((?<=[^\p{L}\d])[\p{L}\d])|(?<=[\p{L}\d\s])([^\p{L}\d\s])/
    List matches = getMatches(text, pattern);

    List candidates = matches.findAll { it > currentPos };
    int endIndex = (currentPos == length) ? length - 1 : length
    int newPos = (candidates[number - 1]) ?: endIndex;

    executeGoForwardToOperation(currentPos, newPos, text)
  }

  void executeGoForwardToOperation(int currentPos, int newPos,
                                   String text) {
    // Do nothing if at end of segment
    if (currentPos == text.length()) {
      return
    }

    if (keyManager.getOperator() == Operator.DELETE) {
      register.push(text[currentPos..(newPos - 1)]);
      deleteToPos(currentPos, newPos);
    } else if (keyManager.getOperator() == Operator.CHANGE) {
      register.push(text[currentPos..(newPos - 1)]);
      changeToPos(currentPos, newPos);
    } else if (keyManager.getOperator() == Operator.YANK) {
      register.push(text[currentPos..(newPos - 1)]);
    } else if (keyManager.getCurrentModeID() == ModeID.VISUAL) {
      selectToPos(currentPos, newPos);
    } else {
      placeCaret(newPos);
    }
  }

  void executeGoBackToOperation(int currentPos, int newPos,
                                   String text) {
    // Do nothing if at beginning of segment
    if (currentPos == 0) {
      return
    }

    if (keyManager.getOperator() == Operator.DELETE) {
      register.push(text[newPos..(currentPos - 1)]);
      deleteToPos(newPos, currentPos);
    } else if (keyManager.getOperator() == Operator.CHANGE) {
      register.push(text[newPos..(currentPos - 1)]);
      changeToPos(newPos, currentPos);
    } else if (keyManager.getOperator() == Operator.YANK) {
      register.push(text[newPos..(currentPos - 1)]);
    } else if (keyManager.getCurrentModeID() == ModeID.VISUAL) {
      selectToPos(newPos, currentPos);
    } else {
      placeCaret(newPos);
    }
  }

  void goForwardToChar(int number, String key) {
    // Escape chars that need to be escaped in char class
    key = key.replaceAll(/([\[\]\^\-])/, $/\\$1/$)
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    String text = editor.getCurrentTranslation();
    int length = text.length();
    Pattern pattern = ~/[$key]/
    List matches = getMatches(text, pattern);
    List candidates = matches.findAll { it > currentPos };
    int newPos = (candidates[number - 1]) ?: currentPos;

    executeGoForwardToOperation(currentPos, newPos, text)
  }

  void goBackwardToChar(int number, String key) {
    // Escape chars that need to be escaped in char class
    key = key.replaceAll(/([\[\]\^\-])/, $/\\$1/$)
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    String text = editor.getCurrentTranslation();
    int length = text.length();
    Pattern pattern = ~/[$key]/
    List matches = getMatches(text, pattern);
    List candidates = matches.findAll { it < currentPos };
    int newPos = (candidates[-number]) ?: currentPos;
    executeGoBackToOperation(currentPos, newPos, text)
  }

  void sneakBackToChars(String keypair) {
    // Escape chars that need to be escaped in char class
    String firstKey = keypair[0]
    String secondKey = keypair[1]
    (firstKey, secondKey) = [firstKey, secondKey].collect {
      it = it.replaceAll(/([\[\]\^\-])/, $/\\$1/$)
    }
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    String text = editor.getCurrentTranslation();
    int length = text.length();
    Pattern pattern = ~/[$firstKey][$secondKey]/
    List matchingIndices = getMatchingIndices(text, pattern);
    List candidates = matchingIndices.findAll { it < currentPos };
    int newPos = (!!candidates) ? candidates[-1] : currentPos;

    executeGoBackToOperation(currentPos, newPos, text)
  }

  void sneakForwardToChars(String keypair) {
    // Escape chars that need to be escaped in char class
    String firstKey = keypair[0]
    String secondKey = keypair[1]
    (firstKey, secondKey) = [firstKey, secondKey].collect {
      it = it.replaceAll(/([\[\]\^\-])/, $/\\$1/$)
    }
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    String text = editor.getCurrentTranslation();
    int length = text.length();
    Pattern pattern = ~/[$firstKey][$secondKey]/
    List matchingIndices = getMatchingIndices(text, pattern);
    List candidates = matchingIndices.findAll { it > currentPos };
    int newPos = (!!candidates) ? candidates[0] : currentPos;

    executeGoForwardToOperation(currentPos, newPos, text)
  }

  List getMatchingIndices(String text, Pattern pattern) {
    Matcher matcher = pattern.matcher(text);
    List matches = [];

    while (matcher.find()) {
      matches << matcher.start();
    }
    return matches;
  }

  List getMatches(String text, Pattern pattern) {
    Matcher matcher = pattern.matcher(text);
    List matches = [];

    while (matcher.find()) {
      matches << matcher.start();
    }
    return matches;
  }

  void moveCaret(int positionChange) {
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    int newPos = currentPos + positionChange;
    String text = editor.getCurrentTranslation();

    if (positionChange > 0) {
      executeGoForwardToOperation(currentPos, newPos, text)
    } else {
      executeGoBackToOperation(currentPos, newPos, text)    
    }
  }

  void moveToLineStart() {
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    int newPos = 0
    String text = editor.getCurrentTranslation();

    executeGoBackToOperation(currentPos, newPos, text)
  }

  void moveToLineEnd() {
    int currentPos = editor.getCurrentPositionInEntryTranslation();
    String text = editor.getCurrentTranslation();
    int newPos = text.length();

    executeGoForwardToOperation(currentPos, newPos, text);
  }

  void deleteToLineEnd() {
    keyManager.switchTo(ModeID.OPERATOR_PENDING,
                        Operator.DELETE);
    moveToLineEnd();
  }

  void changeToLineEnd() {
    keyManager.switchTo(ModeID.OPERATOR_PENDING,
                        Operator.CHANGE);
    moveToLineEnd();
  }
}

class Register {
  String content;
  Clipboard clipboard;

  Register() {
    clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
  }

  void push(String yankContent) {
    content = yankContent;
    StringSelection data = new StringSelection(content);
    clipboard.setContents(data, data);
  }

  String getContent() {
    content;
  } 
}

class ShapeShiftingCaret extends DefaultCaret {
  EditorTextArea3 pane;
  Boolean isBlockCaret;

  ShapeShiftingCaret(EditorTextArea3 pane) {
    this.pane = pane;
    isBlockCaret = false;
  }
  
  void paint(Graphics g) {
    if (isBlockCaret) {
      int caretWidth = getCaretWidth();
      pane.putClientProperty("caretWidth", caretWidth);
      g.setXORMode(Styles.EditorColor.COLOR_FOREGROUND.getColor());
      g.translate(caretWidth / 2, 0);
      super.paint(g);
    } else {
      super.paint(g);
    }
  }

  synchronized void damage(Rectangle r) {
    // if (['NormalMode', 'OperatorPendingMode',
    //      'VisualMode'].contains(keyManager.currentMode.class.name)) {
    if (isBlockCaret) {
      if (r != null) {
        int damageWidth = getCaretWidth();
        x = r.x - 4 - (damageWidth / 2);
        y = r.y;
        width = 9 + 3 * damageWidth / 2;
        height = r.height;
        repaint();
      }
    } else {
      super.damage(r);
    }
  }

  int getCaretWidth() {
    FontMetrics fm = pane.getFontMetrics(pane.getFont());
    int carWidth = 1;
    try {
      carWidth = fm.stringWidth(pane.getText(pane.getCaretPosition(), 1));
    } catch (BadLocationException e) {
      /* empty */
    }
    return carWidth;
  }
}

class CaretUtilities {
  static void switchCaretShape(pane, modeID) {
    // ShapeShiftingCaret caret = (ShapeShiftingCaret) pane.getCaret();
    ShapeShiftingCaret caret = pane.getCaret();
    // Don't switch caret if modeID is NORMAL and caret is
    // already block (like when you come out of operator pending mode)
    if (!isTransitionFromOperatorPendingMode(modeID, caret)) {
      caret.isBlockCaret = !caret.isBlockCaret;
    }
    if (caret.isBlockCaret) {
      // Change the caret shape, width and color
      pane.setCaretColor(Styles.EditorColor.COLOR_BACKGROUND.getColor());
      pane.putClientProperty("caretWidth", pane.getCaretWidth());
  
      // We need to force the caret damage to have the rectangle to correctly show up,
      // otherwise half of the caret is shown.
      try {
        // ShapeShiftingCaret caret = (ShapeShiftingCaret) pane.getCaret();
        Rectangle r = Java8Compat.modelToView(pane, caret.getDot());
        caret.damage(r);
      } catch (BadLocationException e) {
        e.printStackTrace();
      }
    } else {
      // reset to default insert caret
      pane.setCaretColor(Styles.EditorColor.COLOR_FOREGROUND.getColor());
      pane.putClientProperty("caretWidth", 1);
    }
  }

  static boolean isTransitionFromOperatorPendingMode(modeID, caret) {
    modeID == ModeID.NORMAL && caret.isBlockCaret
  }

  static void setUpShapeShiftingCaret(pane) {
    // Get pre-script caret position
    int pos = pane.getCaretPosition();

    ShapeShiftingCaret c = new ShapeShiftingCaret(pane);
    pane.setCaret(c);

    // Reset pre-script caret position
    // Note that this EditorTextArea3#setCaretPosition (the
    // standard JTextComponent method) is not the same method as
    // EditorController#setCaretPosition
    pane.setCaretPosition(pos);

    switchCaretShape(pane, ModeID.NORMAL);
  }

  static void resetCaret(pane) {
    switchCaretShape(pane, ModeID.INSERT);
    pane.repaint();
    pane.revalidate();
  }
}

if (binding.hasVariable('testing')) {
  editor = new EditorController();
  testWindow.setup(editor.editor);
} else {
  pane = editor.editor;
  CaretUtilities.setUpShapeShiftingCaret(pane);
}

new Listener(editor, editor.editor);
