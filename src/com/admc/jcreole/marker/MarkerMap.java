/*
 * Copyright 2011 Axis Data Management Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.admc.jcreole.marker;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.admc.jcreole.CreoleParseException;
import com.admc.jcreole.TagType;

public class MarkerMap extends HashMap<Integer, BufferMarker> {
    private static Log log = LogFactory.getLog(MarkerMap.class);
private String[] classz = { "alpha", "beta", "gamma", "delta", "mu", "nu", "omicron" };
int nextOne = 0;

    public String apply(StringBuilder sb) {
        int offset = 0;
        BufferMarker marker;
        List<Integer> markerOffsets = new ArrayList<Integer>();
        String idString;
        int id;
        while ((offset = sb.indexOf("\u001a", offset)) > -1) {
            // Unfortunately StringBuilder has no indexOf(char).
            // We could make do StringBuilder.toString().indexOf(char), but
            // that's a pretty expensive copy operation.
            markerOffsets.add(Integer.valueOf(offset));
            if (sb.length() < offset + 4)
                throw new CreoleParseException(
                        "Marking too close to end of output");
            idString = sb.substring(offset + 1, offset + 5);
            id = Integer.parseInt(idString, 16);
            marker = get(Integer.valueOf(id));
            if (marker == null)
                throw new IllegalStateException("Lost marker with id " + id);
            marker.setContext(sb, offset);
            offset += 5;  // Move past the marker that we just found
        }
        List<BufferMarker> sortedMarkers = new ArrayList(values());
        Collections.sort(sortedMarkers);
        log.warn(Integer.toString(markerOffsets.size())
                + " markings: " + markerOffsets);
        if (markerOffsets.size() != sortedMarkers.size())
            throw new IllegalStateException(
                    "Markings/markers mismatch.  " + markerOffsets.size()
                    + " markings found, but there are " + size()
                    + " markers");
        // Can not run insert() until after the markers have been sorted.
        if (size() > 0) {
            validateAndSetClasses(sortedMarkers);
            // The list of markers MUST BE REVERSE SORTED before applying.
            // Applying in forward order would change buffer offsets.
            Collections.reverse(sortedMarkers);
            StringBuilder markerReport = new StringBuilder();
            for (BufferMarker m : sortedMarkers) {
                if (markerReport.length() > 0) markerReport.append(", ");
                markerReport.append(m.getIdString()
                        + '@' + m.getOffset());
                // N.b. this is where the real APPLY occurs to the buffer:
                m.updateBuffer();
            }
            log.warn("MARKERS:  " + markerReport.toString());
        }
        return sb.toString();
    }

    /**
     * Validates tag nesting and updates CSS classes of all TagMarkers.
     *
     * For efficiency of the iteration, these two disparate functions are both
     * performed by this one function.
     */
    private void validateAndSetClasses(List<BufferMarker> sortedMarkers) {
        List<TagMarker> stack = new ArrayList<TagMarker>();
        List<? extends TagMarker> typedStack = null;
        List<String> queuedJcxClassNames = new ArrayList<String>();
        List<String> queuedBlockClassNames = new ArrayList<String>();
        List<String> queuedInlineClassNames = new ArrayList<String>();
        List<String> typedQueue = null;
        CloseMarker closeM;
        TagMarker lastTag, tagM;
        List<JcxSpanMarker> jcxStack = new ArrayList<JcxSpanMarker>();
        List<BlockMarker> blockStack = new ArrayList<BlockMarker>();
        List<InlineMarker> inlineStack = new ArrayList<InlineMarker>();
        JcxSpanMarker prevJcx = null;
        BlockMarker prevBlock = null;
        InlineMarker prevInline = null;
        for (BufferMarker m : sortedMarkers) {
            if (m instanceof TagMarker) {
                tagM = (TagMarker) m;
                // Get this validation over with so rest of this block can
                // assume tagM is an instance of one of these types.
                if (!(tagM instanceof JcxSpanMarker)
                        && !(tagM instanceof BlockMarker)
                        && !(tagM instanceof InlineMarker))
                    throw new RuntimeException(
                            "Unexpected class for TagMarker " + tagM
                            + ": " + tagM.getClass().getName());
                // UPDATE prev/cur
                if (tagM.isAtomic()) {
                    // For atomics we do not deal with stacks, since we would
                    // just push and pop immediately resulting in no change.
                    // Similarly, whatever was cur* before will again be cur*
                    // when we exit this code block.
                    if (tagM instanceof JcxSpanMarker) {
                        prevJcx = (JcxSpanMarker) tagM;
                    } else if (tagM instanceof BlockMarker) {
                        prevBlock = (BlockMarker) tagM;
                    } else if (tagM instanceof InlineMarker) {
                        prevInline = (InlineMarker) tagM;
                    }
                } else {
                    // Tag has just OPENed.
                    if (tagM instanceof JcxSpanMarker) {
                        prevJcx = (jcxStack.size() > 0)
                                ? jcxStack.get(jcxStack.size()-1)
                                : null;
                        jcxStack.add((JcxSpanMarker) tagM);
                    } else if (tagM instanceof BlockMarker) {
                        prevBlock = (blockStack.size() > 0)
                                ? blockStack.get(blockStack.size()-1)
                                : null;
                        blockStack.add((BlockMarker) tagM);
                    } else if (tagM instanceof InlineMarker) {
                        prevInline = (inlineStack.size() > 0)
                                ? inlineStack.get(inlineStack.size()-1)
                                : null;
                        inlineStack.add((InlineMarker) tagM);
                    }
                    stack.add(tagM);  // 'lastTag' until another added
                }
                if (tagM instanceof JcxSpanMarker) {
                    if (queuedJcxClassNames.size() > 0)
                        typedQueue = queuedJcxClassNames;
                } else if (tagM instanceof BlockMarker) {
                    if (queuedBlockClassNames.size() > 0)
                        typedQueue = queuedBlockClassNames;
                } else if (tagM instanceof InlineMarker) {
                    if (queuedInlineClassNames.size() > 0)
                        typedQueue = queuedInlineClassNames;
                } else {
                    typedQueue = null;
                }
                if (typedQueue != null) {
                    for (String className : typedQueue) tagM.add(className);
                    typedQueue.clear();
                }
            } else if (m instanceof CloseMarker) {
                closeM = (CloseMarker) m;
                lastTag = (stack.size() > 0) ? stack.get(stack.size()-1) : null;
                // Validate tag name
                if (lastTag == null
                        || !lastTag.getTagName().equals(closeM.getTagName()))
                    throw new CreoleParseException(
                            "Tangled tag nesting.  No matching open tag name "
                            + "for close of " + closeM + ".  Last open tag is "
                            + lastTag + '.');
                Boolean blockType = closeM.getBlockType();
                // Validate tag type
                if ((blockType == null && !(lastTag instanceof JcxSpanMarker))
                        || (blockType == Boolean.TRUE
                        && !(lastTag instanceof BlockMarker))
                        || (blockType == Boolean.FALSE
                        && !(lastTag instanceof InlineMarker)))
                    throw new CreoleParseException(
                            "Tangled tag nesting.  No matching open tag type "
                            + "for close of " + closeM + ".  Last open tag is "
                            + lastTag + '.');
                if (lastTag.isAtomic())
                    throw new CreoleParseException(
                            "Close tag " + closeM
                            + " attempted to close atomic tag "
                            + lastTag + '.');
                // Get this validation over with so rest of this block can
                // assume lastTag is an instance of one of these types.
                if (!(lastTag instanceof JcxSpanMarker)
                        && !(lastTag instanceof BlockMarker)
                        && !(lastTag instanceof InlineMarker))
                    throw new RuntimeException(
                            "Unexpected class for TagMarker " + lastTag
                            + ": " + lastTag.getClass().getName());
                // At this point we have validated match with an opening tag.
                if (lastTag instanceof JcxSpanMarker) {
                    prevJcx = (JcxSpanMarker) lastTag;
                    typedStack = jcxStack;
                } else if (lastTag instanceof BlockMarker) {
                    prevBlock = (BlockMarker) lastTag;
                    typedStack = blockStack;
                } else if (lastTag instanceof InlineMarker) {
                    prevInline = (InlineMarker) lastTag;
                    typedStack = inlineStack;
                }
                if (typedStack.size() < 1
                        || typedStack.get(typedStack.size()-1) != lastTag)
                    throw new CreoleParseException(
                            "Closing tag " + lastTag
                            + ", but it is not on the tail of the "
                            + "type-specific tag stack: " + typedStack);
                typedStack.remove(typedStack.size()-1);
                stack.remove(stack.size()-1);
            } else if (m instanceof Styler) {
                Styler styler = (Styler) m;
                TagType targetType = styler.getTargetType();
                String className = styler.getClassName();
                // Get this validation over with so rest of this block can
                // assume targetType is an instance of one of these types.
                switch (targetType) {
                  case INLINE:
                  case BLOCK:
                  case JCX:
                    break;
                  default:
                    throw new RuntimeException(
                            "Unexpected tag type value: " + targetType);
                }
                TagMarker targetTag = null;
                switch (styler.getTargetDirection()) {
                  case PREVIOUS:
                    switch (targetType) {
                      case INLINE:
                        targetTag = prevInline;
                        break;
                      case BLOCK:
                        targetTag = prevBlock;
                        break;
                      case JCX:
                        targetTag = prevJcx;
                        break;
                    }
                    if (targetTag == null)
                        throw new CreoleParseException(
                                "No previous " + targetType
                                + " tag for Styler " + styler);
                    break;
                  case CONTAINER:
                    switch (targetType) {
                      case INLINE:
                        typedStack = inlineStack;
                        break;
                      case BLOCK:
                        typedStack = blockStack;
                        break;
                      case JCX:
                        typedStack = jcxStack;
                        break;
                    }
                    if (typedStack.size() < 1)
                        throw new CreoleParseException(
                                "No parent " + targetType
                                + " container for Styler " + styler);
                    targetTag = typedStack.get(typedStack.size()-1);
                    break;
                  case NEXT:
                    switch (targetType) {
                      case INLINE:
                        typedQueue = queuedJcxClassNames;
                        break;
                      case BLOCK:
                        typedQueue = queuedBlockClassNames;
                        break;
                      case JCX:
                        typedQueue = queuedInlineClassNames;
                        break;
                    }
                    typedQueue.add(className);
                    break;
                  default:
                    throw new RuntimeException("Unexpected direction value: "
                            + styler.getTargetDirection());
                }
                if (targetTag != null) targetTag.add(className);
            } else {
                throw new CreoleParseException(
                        "Unexpected close marker class: "
                        + m.getClass().getName());
            }
        }
        if (stack.size() != 0)
            throw new CreoleParseException(
                    "Unmatched tag(s) generated: " + stack);
        if (jcxStack.size() != 0)
            throw new CreoleParseException(
                    "Unmatched JCX tag(s): " + jcxStack);
        if (blockStack.size() != 0)
            throw new CreoleParseException(
                    "Unmatched Block tag(s): " + blockStack);
        if (inlineStack.size() != 0)
            throw new CreoleParseException(
                    "Unmatched Inline tag(s): " + inlineStack);
        if (queuedJcxClassNames.size() > 0)
            throw new CreoleParseException(
                    "Unapplied Styler JCX class names: " + queuedJcxClassNames);
        if (queuedBlockClassNames.size() > 0)
            throw new CreoleParseException(
                    "Unapplied Styler Block class names: "
                    + queuedBlockClassNames);
        if (queuedInlineClassNames.size() > 0)
            throw new CreoleParseException(
                    "Unapplied Styler Inline class names: "
                    + queuedInlineClassNames);
    }
}