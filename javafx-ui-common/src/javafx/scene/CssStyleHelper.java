/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package javafx.scene;

import com.sun.javafx.Logging;
import com.sun.javafx.Utils;
import com.sun.javafx.css.CalculatedValue;
import static com.sun.javafx.css.CalculatedValue.SKIP;
import com.sun.javafx.css.CascadingStyle;
import com.sun.javafx.css.CssError;
import com.sun.javafx.css.Declaration;
import com.sun.javafx.css.ParsedValueImpl;
import com.sun.javafx.css.PseudoClassState;
import com.sun.javafx.css.Rule;
import com.sun.javafx.css.Selector;
import com.sun.javafx.css.Style;
import com.sun.javafx.css.StyleCache;
import com.sun.javafx.css.StyleCacheEntry;
import com.sun.javafx.css.StyleConverterImpl;
import com.sun.javafx.css.StyleManager;
import com.sun.javafx.css.StyleMap;
import com.sun.javafx.css.Stylesheet;
import com.sun.javafx.css.converters.FontConverter;
import com.sun.javafx.css.parser.CSSParser;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import javafx.css.CssMetaData;
import javafx.css.FontCssMetaData;
import javafx.css.PseudoClass;
import javafx.css.StyleConverter;
import javafx.css.StyleOrigin;
import javafx.css.Styleable;
import javafx.css.StyleableProperty;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import sun.util.logging.PlatformLogger;

/**
 * The StyleHelper is a helper class used for applying CSS information to Nodes.
 */
final class CssStyleHelper {

    private static final PlatformLogger LOGGER = com.sun.javafx.Logging.getCSSLogger();

    private CssStyleHelper() {  
        this.triggerStates = new PseudoClassState();
    }
    
    /**
     * Creates a new StyleHelper.
     */
    static CssStyleHelper createStyleHelper(Node node) {
        
        // need to know how far we are to root in order to init arrays.
        // TODO: should we hang onto depth to avoid this nonsense later?
        // TODO: is there some other way of knowing how far from the root a node is?
        Node parent = node;
        int depth = 0;
        while(parent != null) {
            depth++;
            parent = parent.getParent();
        }
                
        // The List<CacheEntry> should only contain entries for those
        // pseudo-class states that have styles. The StyleHelper's
        // pseudoclassStateMask is a bitmask of those pseudoclasses that
        // appear in the node's StyleHelper's smap. This list of
        // pseudo-class masks is held by the StyleCacheKey. When a node is
        // styled, its pseudoclasses and the pseudoclasses of its parents
        // are gotten. By comparing the actual pseudo-class state to the
        // pseudo-class states that apply, a CacheEntry can be created or
        // fetched using only those pseudoclasses that matter.
        final PseudoClassState[] triggerStates = new PseudoClassState[depth];
        
        final StyleMap styleMap = 
                StyleManager.getInstance().findMatchingStyles(node, triggerStates);

        
        final Map<String, List<CascadingStyle>> smap 
                = styleMap != null ? styleMap.getMap() : null;
        
        if (smap == null || smap.isEmpty()) {
            
            // If there are no styles at all, and no styles that inherit, then return
            final String inlineStyle = node.getStyle();
            if (inlineStyle == null || inlineStyle.trim().isEmpty()) {
                
                boolean mightInherit = false;
                final List<CssMetaData<? extends Styleable, ?>> props = node.getCssMetaData();
                
                final int pMax = props != null ? props.size() : 0;
                for (int p=0; p<pMax; p++) {
                    
                    final CssMetaData<? extends Styleable, ?> prop = props.get(p);
                    if (prop.isInherits()) {
                        mightInherit = true;
                        break;
                    }
                }
                
                if (mightInherit == false) {
                    
                    // If this node had a style helper but no longer has
                    // a StyleHelper, then reset properties to thier initial
                    // value. If this node were to have a StyleHelper after
                    // exiting this method, then transitionToState would take
                    // care of resetting properties if needed. 
                    if (node.styleHelper != null) {
                        node.styleHelper.resetToInitialValues(node);
                    }
                    
                    return null;
                }
            }
        }   
        
        final CssStyleHelper helper = new CssStyleHelper();
        
        helper.triggerStates.addAll(triggerStates[0]);

        // make sure parent's transition states include the pseudo-classes 
        // found when matching selectors
        parent = node.getParent();
        for(int n=1; n<depth; n++) {

            final PseudoClassState triggerState = triggerStates[n];

            // if there is nothing in triggerState, then continue since there
            // isn't any pseudo-class state that might trigger a state change
            if (triggerState != null && triggerState.size() > 0) {

                // Create a StyleHelper for the parent, if necessary. 
                if (parent.styleHelper == null) {
                    parent.styleHelper = new CssStyleHelper();
                }
                parent.styleHelper.triggerStates.addAll(triggerState);
                
            }   
            
            parent=parent.getParent();
        }         
        
        helper.cacheMetaData = new CacheMetaData(node, styleMap, depth);
        return helper;
    }
    
    private CacheMetaData cacheMetaData;
        
    private final static class CacheMetaData {

        // Set internal internalState structures
        private CacheMetaData(
                Node node, 
                StyleMap styleMap, 
                int depth) {

            int ctr = 0;
            int[] smapIds = new int[depth];
            smapIds[ctr++] = this.smapId = styleMap.getId();
            
            //
            // Create a set of StyleMap id's from the parent's smapIds.
            // The resulting smapIds array may have less than depth elements.
            // If a parent doesn't have a styleHelper or the styleHelper's
            // internal state is null, then that parent doesn't contribute
            // to the selection of a style. Any Node that has the same
            // set of smapId's can potentially share previously calculated
            // values.
            //
            Parent parent = node.getParent();
            for(int d=1; d<depth; d++) {
                
                final CssStyleHelper helper = parent.styleHelper;
                if (helper != null && helper.cacheMetaData != null) {                
                    smapIds[ctr++] = helper.cacheMetaData.smapId;
                }
                parent = parent.getParent();
                
            }

            this.localStyleCache = new StyleCache();
            
            final Map<StyleCache.Key,StyleCache> styleCache = 
                    StyleManager.getInstance().getStyleCache();
            
            final StyleCache.Key styleCacheKey = new StyleCache.Key(smapIds, ctr);
            StyleCache sharedCache = styleCache.get(styleCacheKey);
            
            if (sharedCache == null) {
                sharedCache = new StyleCache();
                styleCache.put(styleCacheKey, sharedCache);
            }

            this.sharedCacheRef = new WeakReference<StyleCache>(sharedCache);
            
            CssMetaData<Styleable,Font> styleableFontProperty = null;
            
            final List<CssMetaData<? extends Styleable, ?>> props = node.getCssMetaData();
            final int pMax = props != null ? props.size() : 0;
            for (int p=0; p<pMax; p++) {
                final CssMetaData<? extends Styleable, ?> prop = props.get(p);
            
                if ("-fx-font".equals(prop.getProperty())) {
                    // unchecked!
                    styleableFontProperty = (CssMetaData<Styleable, Font>) prop;
                    break;
                }
            }

            this.fontProp = styleableFontProperty;
        
        }

        private final CssMetaData<Styleable,Font> fontProp;
        private final int smapId;
        private final StyleCache localStyleCache;
        private final Reference<StyleCache> sharedCacheRef;
        private StyleCacheEntry previousCacheEntry;
    }
    
    //
    // Find the entry in local cache and in the shared cache that matches the
    // states. Whether or not this is a newly created StyleCacheEntry is returned
    // in isNewEntry[0].
    private StyleCacheEntry getStyleCacheEntry(Node node, Set<PseudoClass>[] transitionStates) {

        if (cacheMetaData == null) return null;
        
        //
        // StyleHelper#triggerStates is the set of pseudo-classes that appear
        // in the style maps of this StyleHelper. Calculated values are
        // cached by pseudo-class state, but only the pseudo-class states
        // that mater are used in the search. So we take the transition states
        // and intersect them with triggerStates to remove the
        // transition states that don't matter when it comes to matching states
        // on a  selector. For example if the style map contains only
        // .foo:hover { -fx-fill: red; } then only the hover state matters
        // but the transtion state could be [hover, focused]
        //
        final Set<PseudoClass>[] pclassMask = new PseudoClassState[transitionStates.length];
        
        int count = 0;
        int depth = 0;
        Node parent = node;
        while (parent != null) {
            final CssStyleHelper helper = parent.styleHelper;
            if (helper != null) {
                pclassMask[count] = new PseudoClassState();
                pclassMask[count].addAll(transitionStates[depth]);
                pclassMask[count].retainAll(helper.triggerStates);
                count += 1;
            }
            depth += 1;
            parent = parent.getParent();
        }

        StyleCacheEntry.Key key = new StyleCacheEntry.Key(pclassMask, count);
                
        StyleCacheEntry localCacheEntry = cacheMetaData.localStyleCache.getStyleCacheEntry(key);
        if (localCacheEntry == null) {
            
            StyleCache sharedCache = cacheMetaData.sharedCacheRef.get();
            if (sharedCache != null) {
                
                StyleCacheEntry sharedCacheEntry = sharedCache.getStyleCacheEntry(key);
                if (sharedCacheEntry == null) {
                    sharedCacheEntry = new StyleCacheEntry();
                    sharedCache.putStyleCacheEntry(key, sharedCacheEntry);
                }
                
                localCacheEntry = new StyleCacheEntry(sharedCacheEntry);
                cacheMetaData.localStyleCache.putStyleCacheEntry(key, localCacheEntry);

            }
        }

        return localCacheEntry;
        
    }
    
    void inlineStyleChanged(Node node) {

        // Clear local cache so styles will be recalculated. 
        // Since we're clearing the cache and getting (potentially) 
        // new styles, reset the properties to initial values.
        if (cacheMetaData != null) {
            
            cacheMetaData.localStyleCache.clear();
            
            // do we have any styles at all now?
            final String inlineStyle = node.getStyle();
            if(inlineStyle == null || inlineStyle.isEmpty()) {

                final Map<String, List<CascadingStyle>> smap = getStyleMap();            
                if (smap == null || smap.isEmpty()) {
                    // We have no styles! Reset this StyleHelper to its
                    // initial state so that calls to transitionToState 
                    // become a no-op.
                    cacheMetaData = null;
                    resetToInitialValues(node);
                }
                
                // If smap isn't empty, then there are styles that
                // apply to the node. There isn't a need to remap the styles
                // since we've only removed an inline style and inline styles
                // aren't part of the style map.
            }

            
            
            
        } else {
            // if cacheMetaData was null
            final String inlineStyle = node.getStyle();
            if (inlineStyle == null || inlineStyle.isEmpty()) {
                return;
            }
            // if we don't have a cacheMetaData, that means this 
            // node doesn't have any applicable styles and it didn't
            // have an inline style before. If it did have an inline
            // style before, then there would be cacheMetaData.  
            // But now the  node does have an inline style and so it
            // needs to have an smap and localStyleCache for the logic
            // in transitionToState to work. 
            Node parent = node;
            int depth = 0;
            while(parent != null) {
                depth++;
                parent = parent.getParent();
            }

            cacheMetaData = new CacheMetaData(node, StyleMap.EMPTY_MAP, depth);
        }
        
    }
    
    private void resetToInitialValues(Styleable styleable) {
        
        final List<CssMetaData<? extends Styleable, ?>> metaDataList = styleable.getCssMetaData();
        final int nStyleables = metaDataList != null ? metaDataList.size() : 0;
        for (int n=0; n<nStyleables; n++) {
            final CssMetaData metaData = metaDataList.get(n);
            if (metaData.isSettable(styleable) == false) continue;
            final StyleableProperty<?> styleableProperty = metaData.getStyleableProperty(styleable);
            if (styleableProperty != null) {
                final StyleOrigin origin = styleableProperty.getStyleOrigin();
                if (origin != null && origin != StyleOrigin.USER) {
                    // If a property is never set by the user or by CSS, then 
                    // the StyleOrigin of the property is null. So, passing null 
                    // here makes the property look (to CSS) like it was
                    // initialized but never used.
                    metaData.set(styleable, metaData.getInitialValue(styleable), null);
                }
            }
        }        
    }
    
        
    private Map<String, List<CascadingStyle>> getStyleMap() {
        if (cacheMetaData == null) return null;
        StyleMap styleMap = StyleManager.getInstance().getStyleMap(cacheMetaData.smapId);
        return (styleMap != null) ? styleMap.getMap() : null;
    }
    
    /** 
     * Cache of parsed, inline styles. The key is Node.style. 
     * The value is the set of property styles from parsing Node.style.
     */
    private static final Map<String,Map<String,CascadingStyle>> inlineStylesCache =
        new HashMap<String,Map<String,CascadingStyle>>();

    /**
     * Get the map of property to style from the rules and declarations
     * in the stylesheet. There is no need to do selector matching here since
     * the stylesheet is from parsing Node.style.
     */
    private static Map<String,CascadingStyle> getInlineStyleMap(Stylesheet inlineStylesheet) {

        final Map<String,CascadingStyle> inlineStyleMap = new HashMap<String,CascadingStyle>();
        if (inlineStylesheet != null) {
            // Order in which the declaration of a CascadingStyle appears (see below)
            int ordinal = 0;
            // For each declaration in the matching rules, create a CascadingStyle object
            final List<Rule> stylesheetRules = inlineStylesheet.getRules();
            for (int i = 0, imax = stylesheetRules.size(); i < imax; i++) {
                final Rule rule = stylesheetRules.get(i);
                final List<Declaration> declarations = rule.getDeclarations();
                for (int k = 0, kmax = declarations.size(); k < kmax; k++) {
                    Declaration decl = declarations.get(k);

                    CascadingStyle s = new CascadingStyle(
                        new Style(Selector.getUniversalSelector(), decl),
                        NULL_PSEUDO_CLASS_STATE, // no pseudo classes
                        0, // specificity is zero
                        // ordinal increments at declaration level since
                        // there may be more than one declaration for the
                        // same attribute within a rule or within a stylesheet
                        ordinal++
                    );

                    inlineStyleMap.put(decl.getProperty(),s);
                }
            }
        }
        return inlineStyleMap;
    }

    /**
     * Get the mapping of property to style from Node.style for this node.
     */
    private static Map<String,CascadingStyle> getInlineStyleMap(Styleable styleable) {
        
        final String inlineStyles = styleable.getStyle();

        // If there are no styles for this property then we can just bail
        if ((inlineStyles == null) || inlineStyles.isEmpty()) return null;

        Map<String,CascadingStyle> styles = inlineStylesCache.get(inlineStyles);
        
        if (styles == null) {

            Stylesheet inlineStylesheet =
                CSSParser.getInstance().parseInlineStyle(styleable);
            if (inlineStylesheet != null) {
                inlineStylesheet.setOrigin(StyleOrigin.INLINE);
            }
            styles = getInlineStyleMap(inlineStylesheet);
            
            inlineStylesCache.put(inlineStyles, styles);
        }

        return styles;
    }
    
    /**
     * A Set of all the pseudo-class states which, if they change, need to
     * cause the Node to be set to UPDATE its CSS styles on the next pulse.
     * For example, your stylesheet might have:
     * <pre><code>
     * .button { ... }
     * .button:hover { ... }
     * .button *.label { text-fill: black }
     * .button:hover *.label { text-fill: blue }
     * </code></pre>
     * In this case, the first 2 rules apply to the Button itself, but the
     * second two rules apply to the label within a Button. When the hover
     * changes on the Button, however, we must mark the Button as needing
     * an UPDATE. StyleHelper though only contains styles for the first two
     * rules for Button. The pseudoclassStateMask would in this case have
     * only a single bit set for "hover". In this way the StyleHelper associated
     * with the Button would know whether a change to "hover" requires the
     * button and all children to be update. Other pseudo-class state changes
     * that are not in this hash set are ignored.
     * *
     * Called "triggerStates" since they would trigger a CSS update.
     */
    private PseudoClassState triggerStates = new PseudoClassState();
    
    boolean pseudoClassStateChanged(PseudoClass pseudoClass) {    
        return triggerStates.contains(pseudoClass);
    }    
    
    /**
     * dynamic pseudo-class state of the node and its parents. 
     * Only valid during a pulse. 
     *
     * The StyleCacheEntry to choose depends on the Node's state and
     * the state of its parents. Without the parent state, the fact that
     * the the node in this state matched foo:blah bar { } is lost.
     *
     */ 
    private Set<PseudoClass>[] getTransitionStates(Node node) {

        //
        // Note Well: The array runs from leaf to root. That is, 
        // transitionStates[0] is the pseudo-class state for node and 
        // transitionStates[1..(states.length-1)] are the pseudoclassStates for the 
        // node's parents.
        //
        
        int count = 0;
        Node parent = node;
        while (parent != null) {
            count += 1;
            parent = parent.getParent();
        }
        
        Set<PseudoClass>[] states = new PseudoClassState[count];
        
        count = 0;
        parent = node;
        while (parent != null) {
            states[count] = parent.pseudoClassStates;
            count += 1;
            parent = parent.getParent();
        }        
        
        return states;
        
    }
    
    ObservableMap<StyleableProperty<?>, List<Style>> observableStyleMap;
     /**
      * RT-17293
      */
     ObservableMap<StyleableProperty<?>, List<Style>> getObservableStyleMap() {
         return (observableStyleMap != null) 
             ? observableStyleMap 
             : FXCollections.<StyleableProperty<?>, List<Style>>emptyObservableMap();
     }

     /**
      * RT-17293
      */
     void setObservableStyleMap(ObservableMap<StyleableProperty<?>, List<Style>> observableStyleMap) {
         this.observableStyleMap = observableStyleMap;
     }
          
    
    /**
     * Called by the Node whenever it has transitioned from one set of
     * pseudo-class states to another. This function will then lookup the
     * new values for each of the styleable variables on the Node, and
     * then either set the value directly or start an animation based on
     * how things are specified in the CSS file. Currently animation support
     * is disabled until the new parser comes online with support for
     * animations and that support is detectable via the API.
     */
    void transitionToState(Node node) {
       
        if (cacheMetaData == null) {
            return;
        }
        
        Set<PseudoClass>[] transitionStates = getTransitionStates(node);
                
        //
        // Styles that need lookup can be cached provided none of the styles
        // are from Node.style.
        //                
        StyleCacheEntry cacheEntry = getStyleCacheEntry(node, transitionStates);
        if (cacheEntry == null) {
            cacheMetaData.localStyleCache.clear();
            cacheMetaData = null;
            node.impl_reapplyCSS();
            return;
        }
        //
        // inlineStyles is this node's Node.style. This is passed along and is
        // used in getStyle. Importance being the same, an author style will
        // trump a user style and a user style will trump a user_agent style.
        //
        final Map<String,CascadingStyle> inlineStyles = 
                CssStyleHelper.getInlineStyleMap(node);
        
        boolean fastpath = cacheEntry.getFont() != null;
        if (fastpath == false) {
                        
            final CalculatedValue relativeFont = 
                getFontForUseInConvertingRelativeSize(
                    node, 
                    cacheEntry, 
                    transitionStates[0], 
                    inlineStyles
                );            
            
            assert(relativeFont != null);
            cacheEntry.setFont(relativeFont);

        }
        
        // origin of the font used for converting font-relative sizes
        final StyleOrigin fontOrigin = 
                cacheEntry.getFont() != null 
                ? cacheEntry.getFont().getOrigin() 
                : null;
            
        fastpath = 
                fastpath &&
                // If someone is watching the styles, 
                //   then we have to take the slow path.
                observableStyleMap == null &&
                // If this node has inline-styles, 
                //   then we might not be able to use the value in shared cache.
                inlineStyles == null &&
                // If the relative font came from a user-set value, 
                //   then we might not be able to use the value in shared cache.
                fontOrigin != StyleOrigin.USER && 
                // If the relative font came from an inline-style, 
                //   then we might not be able to use the value in shared cache.
                fontOrigin != StyleOrigin.INLINE;                        
        
        final List<CssMetaData<? extends Styleable, ?>> styleables = node.getCssMetaData();
        
        // Used in the for loop below, and a convenient place to stop when debugging.
        final int max = styleables.size();

        // RT-20643
        CssError.setCurrentScene(node.getScene());
        
        // For each property that is settable, we need to do a lookup and
        // transition to that value.
        for(int n=0; n<max; n++) {

            @SuppressWarnings("unchecked") // this is a widening conversion
            final CssMetaData<Styleable,Object> cssMetaData = 
                    (CssMetaData<Styleable,Object>)styleables.get(n);
            
            if (observableStyleMap != null) {
                StyleableProperty<?> styleableProperty = cssMetaData.getStyleableProperty(node);
                if (styleableProperty != null && observableStyleMap.containsKey(styleableProperty)) {
                    observableStyleMap.remove(styleableProperty);
                }
            }
            

            // Skip the lookup if we know there isn't a chance for this property
            // to be set (usually due to a "bind").
            if (!cssMetaData.isSettable(node)) continue;

            final String property = cssMetaData.getProperty();
            
            // Create a List to hold the Styles if the node has 
            // a Map<WritableValue, List<Style>>
            final List<Style> styleList = (observableStyleMap != null) 
                    ? new ArrayList<Style>() 
                    : null;

            CalculatedValue calculatedValue = null;
            boolean isUserSet = false;
            
            if (fastpath) {

                calculatedValue = cacheEntry.get(property);
                if (calculatedValue == null || calculatedValue == SKIP) continue;
                
                // This block of code is repeated in the slowpath, but we 
                // want to avoid calling getStyleableProperty if possible.
                // See the use of 'if (isUserSet)' below.
                final StyleableProperty styleableProperty = cssMetaData.getStyleableProperty(node);
                if (styleableProperty == null) continue;
                
                final StyleOrigin origin = styleableProperty.getStyleOrigin();
                
                isUserSet = origin == StyleOrigin.USER;
                
            } else {

                final StyleableProperty styleableProperty = cssMetaData.getStyleableProperty(node);
                if (styleableProperty == null) continue;
                
                final StyleOrigin origin = styleableProperty.getStyleOrigin();
                
                isUserSet = origin == StyleOrigin.USER;

                calculatedValue = lookup(node, cssMetaData, isUserSet, node.pseudoClassStates, 
                        inlineStyles, node, cacheEntry, styleList);

                // lookup is not supposed to return null.
                assert(calculatedValue != null);
                
                // RT-19089
                // If the current value of the property was set by CSS 
                // and there is no style for the property, then reset this
                // property to its initial value. 
                //
                if (calculatedValue == SKIP || calculatedValue == null) {
                    
                    // Was value set by CSS?
                    if (origin != StyleOrigin.USER && origin != null) {

                        Object initial = cssMetaData.getInitialValue(node);
                        
                        calculatedValue = new CalculatedValue(initial, null, false);
                        
                    } else {   
                        // was set by user or was never set
                        continue;
                    }
                    
                }
                
                cacheEntry.put(property, calculatedValue);

            }
            
            assert (calculatedValue != SKIP);
            if (calculatedValue == SKIP) continue;
            
                        
            // RT-10522:
            // If the user set the property and there is a style and
            // the style came from the user agent stylesheet, then
            // skip the value. A style from a user agent stylesheet should
            // not override the user set style.
            //
            // RT-21894: the origin might be null if the calculatedValue 
            // comes from reverting back to the initial value. In this case,
            // make sure the initial value doesn't overwrite the user set value.
            // Also moved this condition from the fastpath block to here since
            // the check needs to be done on any calculated value, not just
            // calculatedValues from cache
            //
            final StyleOrigin cvOrigin = calculatedValue.getOrigin();
            if (isUserSet) {
                if (cvOrigin == StyleOrigin.USER_AGENT || cvOrigin == null) { 
                    continue;
                }                
            }
            
                try {
                    
                    final Object value = calculatedValue.getValue();
                    if (LOGGER.isLoggable(PlatformLogger.FINER)) {
                        LOGGER.finer(property + ", call cssMetaData.set(" + 
                                node + ", " + String.valueOf(value) + ", " +
                                cvOrigin + ")");
                    }

                    cssMetaData.set(node, value, cvOrigin);
                    
                    if (observableStyleMap != null) {
                        StyleableProperty<?> styleableProperty = cssMetaData.getStyleableProperty(node);                            
                        observableStyleMap.put(styleableProperty, styleList);
                    }
                    
                } catch (Exception e) {

                    // RT-27155: if setting value raises exception, reset value 
                    // the value to initial and thereafter skip setting the property
                    cssMetaData.set(node, cssMetaData.getInitialValue(node), null);
                    cacheEntry.put(property, SKIP);
                    
                    List<CssError> errors = null;
                    if ((errors = StyleManager.getErrors()) != null) {
                        final String msg = String.format("Failed to set css [%s] due to %s\n", cssMetaData, e.getMessage());
                        final CssError error = new CssError.PropertySetError(cssMetaData, node, msg);
                        errors.add(error);
                    }
                    // TODO: use logger here
                    PlatformLogger logger = Logging.getCSSLogger();
                    if (logger.isLoggable(PlatformLogger.WARNING)) {
                        logger.warning(String.format("Failed to set css [%s]\n", cssMetaData), e);
                    }
                }

            }
        
        cacheMetaData.previousCacheEntry = cacheEntry;
        
        // RT-20643
        CssError.setCurrentScene(null);

        // If the list weren't empty, we'd worry about animations at this
        // point. TODO need to implement animation trickery here
    }

    /**
     * Gets the CSS CascadingStyle for the property of this node in these pseudo-class
     * states. A null style may be returned if there is no style information
     * for this combination of input parameters.
     *
     * @param node 
     * @param property
     * @param states
     * @return
     */
    private CascadingStyle getStyle(Node node, String property, Set<PseudoClass> states, Map<String,CascadingStyle> inlineStyles){

        assert node != null && property != null: String.valueOf(node) + ", " + String.valueOf(property);

        final CascadingStyle inlineStyle = (inlineStyles != null) ? inlineStyles.get(property) : null;

        // Get all of the Styles which may apply to this particular property
        final Map<String, List<CascadingStyle>> smap = getStyleMap();
        if (smap == null) return inlineStyle;

        final List<CascadingStyle> styles = smap.get(property);

        // If there are no styles for this property then we can just bail
        if ((styles == null) || styles.isEmpty()) return inlineStyle;

        // Go looking for the style. We do this by visiting each CascadingStyle in
        // order finding the first that matches the current node & set of
        // pseudo-class states. We use an iteration style that avoids creating
        // garbage iterators (and wish javac did it for us...)
       CascadingStyle style = null;
        final int max = (styles == null) ? 0 : styles.size();
        for (int i=0; i<max; i++) {
            final CascadingStyle s = styles.get(i);
            final Selector sel = s == null ? null : s.getSelector();
            if (sel == null) continue; // bail if the selector is null.
//System.out.println(node.toString() + "\n\tstates=" + PseudoClassSet.getPseudoClasses(states) + "\n\tstateMatches? " + sel.stateMatches(node, states) + "\n\tsel=" + sel.toString());            
            if (sel.stateMatches(node, states)) {
                style = s;
                break;
            }
        }
        
        if (inlineStyle != null) {

            // is inlineStyle more specific than style?    
            if (style == null || inlineStyle.compareTo(style) < 0) {
                style = inlineStyle;
            }

        }
        return style;
    }

    /**
     * The main workhorse of this class, the lookup method walks up the CSS
     * style tree looking for the style information for the Node, the
     * property associated with the given styleable, in these states for this font.
     *
     * @param node
     * @param styleable
     * @param states
     * @param font
     * @return
     */
    private CalculatedValue lookup(Node node, CssMetaData styleable, 
            boolean isUserSet, Set<PseudoClass> states,
            Map<String,CascadingStyle> userStyles, Node originatingNode, 
            StyleCacheEntry cacheEntry, List<Style> styleList) {

        if (styleable.getConverter() == FontConverter.getInstance()) {
        
            return lookupFont(node, styleable, isUserSet, 
                    originatingNode, cacheEntry, styleList);
        }
        
        final String property = styleable.getProperty();

        // Get the CascadingStyle which may apply to this particular property
        CascadingStyle style = getStyle(node, property, states, userStyles);

        // If no style was found and there are no sub styleables, then there
        // are no matching styles for this property. We will then either SKIP
        // or we will INHERIT. We will inspect the default value for the styleable,
        // and if it is INHERIT then we will inherit otherwise we just skip it.
        final List<CssMetaData<? extends Styleable, ?>> subProperties = styleable.getSubProperties();
        final int numSubProperties = (subProperties != null) ? subProperties.size() : 0;
        if (style == null) {
            
            if (numSubProperties == 0) {
                
                return handleNoStyleFound(node, styleable, isUserSet, userStyles, 
                        originatingNode, cacheEntry, styleList);
                
            } else {

                // If style is null then it means we didn't successfully find the
                // property we were looking for. However, there might be sub styleables,
                // in which case we should perform a lookup for them. For example,
                // there might not be a style for "font", but there might be one
                // for "font-size" or "font-weight". So if the style is null, then
                // we need to check with the sub-styleables.

                // Build up a list of all SubProperties which have a constituent part.
                // I default the array to be the size of the number of total
                // sub styleables to avoid having the array grow.
                Map<CssMetaData,Object> subs = null;
                StyleOrigin origin = null;
                
                boolean isRelative = false;
                
                for (int i=0; i<numSubProperties; i++) {
                    CssMetaData subkey = subProperties.get(i);
                    CalculatedValue constituent = 
                        lookup(node, subkey, isUserSet, states, userStyles, 
                            originatingNode, cacheEntry, styleList);
                    if (constituent != SKIP) {
                        if (subs == null) {
                            subs = new HashMap<CssMetaData,Object>();
                        }
                        subs.put(subkey, constituent.getValue());

                        // origin of this style is the most specific
                        if ((origin != null && constituent.getOrigin() != null)
                                ? origin.compareTo(constituent.getOrigin()) < 0
                                : constituent.getOrigin() != null) {
                            origin = constituent.getOrigin();
                        }
                        
                        // if the constiuent uses relative sizes, then 
                        // isRelative is true;
                        isRelative = isRelative || constituent.isRelative();
                            
                    }
                }

                // If there are no subkeys which apply...
                if (subs == null || subs.isEmpty()) {
                    return handleNoStyleFound(node, styleable, isUserSet, userStyles, 
                            originatingNode, cacheEntry, styleList);
                }

                try {
                    final StyleConverter keyType = styleable.getConverter();
                    assert(keyType instanceof StyleConverterImpl);
                    if (keyType instanceof StyleConverterImpl) {
                        Object ret = ((StyleConverterImpl)keyType).convert(subs);
                        return new CalculatedValue(ret, origin, isRelative);
                    } else {
                        return SKIP;
                    }
                } catch (ClassCastException cce) {
                    final String msg = formatExceptionMessage(node, styleable, null, cce);
                    List<CssError> errors = null;
                    if ((errors = StyleManager.getErrors()) != null) {
                        final CssError error = new CssError.PropertySetError(styleable, node, msg);
                        errors.add(error);
                    }
                    if (LOGGER.isLoggable(PlatformLogger.WARNING)) {
                        LOGGER.warning("caught: ", cce);
                        LOGGER.warning("styleable = " + styleable);
                        LOGGER.warning("node = " + node.toString());
                    }
                    return SKIP;
                }
            }                
            
        } else { // style != null

            // RT-10522:
            // If the user set the property and there is a style and
            // the style came from the user agent stylesheet, then
            // skip the value. A style from a user agent stylesheet should
            // not override the user set style.
            if (isUserSet && style.getOrigin() == StyleOrigin.USER_AGENT) {
                return SKIP;
            }

            // If there was a style found, then we want to check whether the
            // value was "inherit". If so, then we will simply inherit.
            final ParsedValueImpl cssValue = style.getParsedValueImpl();
            if (cssValue != null && "inherit".equals(cssValue.getValue())) {
                if (styleList != null) styleList.add(style.getStyle());
                return inherit(node, styleable, userStyles, 
                        originatingNode, cacheEntry, styleList);
            }
        }

//        System.out.println("lookup " + property +
//                ", selector = \'" + style.selector.toString() + "\'" +
//                ", node = " + node.toString());

        if (styleList != null) { 
            styleList.add(style.getStyle());
        }
        
        return calculateValue(style, node, styleable, states, userStyles, 
                    originatingNode, cacheEntry, styleList);
    }
    
    /**
     * Called when there is no style found.
     */
    private CalculatedValue handleNoStyleFound(Node node, CssMetaData styleable,
            boolean isUserSet, Map<String,CascadingStyle> userStyles, 
            Node originatingNode, StyleCacheEntry cacheEntry, List<Style> styleList) {

        if (styleable.isInherits()) {


            // RT-16308: if there is no matching style and the user set 
            // the property, do not look for inherited styles.
            if (isUserSet) {

                    return SKIP;
                    
            }

            CalculatedValue cv =
                inherit(node, styleable, userStyles, 
                    originatingNode, cacheEntry, styleList);

            return cv;

        } else {

            // Not inherited. There is no style
            return SKIP;

        }
    }
    /**
     * Called when we must inherit a value from a parent node in the scenegraph.
     */
    private CalculatedValue inherit(Node node, CssMetaData styleable,
            Map<String,CascadingStyle> userStyles, 
            Node originatingNode, StyleCacheEntry cacheEntry, List<Style> styleList) {
        
        // Locate the first parentStyleHelper in the hierarchy
        Node parent = node.getParent();        
        CssStyleHelper parentStyleHelper = parent == null ? null : parent.styleHelper;
        while (parent != null && parentStyleHelper == null) {
            parent = parent.getParent();
            if (parent != null) {
                parentStyleHelper = parent.styleHelper;
            }
        }

        if (parent == null) {
            return SKIP;
        }
        return parentStyleHelper.lookup(parent, styleable, false,
                parent.pseudoClassStates,
                getInlineStyleMap(parent), originatingNode, cacheEntry, styleList);
    }


    // helps with self-documenting the code
    static final Set<PseudoClass> NULL_PSEUDO_CLASS_STATE = null;
    
    /**
     * Find the property among the styles that pertain to the Node
     */
    private CascadingStyle resolveRef(Node node, String property, Set<PseudoClass> states,
            Map<String,CascadingStyle> inlineStyles) {
        
        final CascadingStyle style = getStyle(node, property, states, inlineStyles);
        if (style != null) {
            return style;
        } else {
            // if style is null, it may be because there isn't a style for this
            // node in this state, or we may need to look up the parent chain
            if (states != null && states.size() > 0) {
                // if states > 0, then we need to check this node again,
                // but without any states.
                return resolveRef(node,property,NULL_PSEUDO_CLASS_STATE,inlineStyles);
            } else {
                // TODO: This block was copied from inherit. Both should use same code somehow.
                Node parent = node.getParent();
                CssStyleHelper parentStyleHelper = parent == null ? null : parent.styleHelper;
                while (parent != null && parentStyleHelper == null) {
                    parent = parent.getParent();
                    if (parent != null) {
                        parentStyleHelper = parent.styleHelper;
                    }
                }

                if (parent == null || parentStyleHelper == null) {
                    return null;
                }
                return parentStyleHelper.resolveRef(parent, property,
                        parent.pseudoClassStates,
                        getInlineStyleMap(parent));
            }
        }
    }

    // to resolve a lookup, we just need to find the parsed value.
    private ParsedValueImpl resolveLookups(
            Node node, 
            ParsedValueImpl value, 
            Set<PseudoClass> states,
            Map<String,CascadingStyle> inlineStyles,
            ObjectProperty<StyleOrigin> whence,
            List<Style> styleList) {
        
        
        assert(value instanceof ParsedValueImpl);
        if ((value instanceof ParsedValueImpl) == false) { return value; }
        
        ParsedValueImpl parsedValue = (ParsedValueImpl)value;
        
        //
        // either the value itself is a lookup, or the value contain a lookup
        //
        if (parsedValue.isLookup()) {

            // The value we're looking for should be a Paint, one of the
            // containers for linear, radial or ladder, or a derived color.
            final Object val = parsedValue.getValue();
            if (val instanceof String) {

                final String sval = (String)val;
                
                CascadingStyle resolved = 
                    resolveRef(node, sval, states, inlineStyles);

                if (resolved != null) {
                    
                    if (styleList != null) {
                        final Style style = resolved.getStyle();
                        if (style != null && !styleList.contains(style)) {
                            styleList.add(style);
                        }
                    }
                    
                    // The origin of this parsed value is the greatest of 
                    // any of the resolved reference. If a resolved reference
                    // comes from an inline style, for example, then the value
                    // calculated from the resolved lookup should have inline
                    // as its origin. Otherwise, an inline style could be 
                    // stored in shared cache.
                    final StyleOrigin wOrigin = whence.get();
                    final StyleOrigin rOrigin = resolved.getOrigin();
                    if (rOrigin != null && (wOrigin == null ||  wOrigin.compareTo(rOrigin) < 0)) {
                        whence.set(rOrigin);
                    } 
                    
                    // the resolved value may itself need to be resolved.
                    // For example, if the value "color" resolves to "base",
                    // then "base" will need to be resolved as well.
                    return resolveLookups(node, resolved.getParsedValueImpl(), states, inlineStyles, whence, styleList);
                }
            }
        }

        // If the value doesn't contain any values that need lookup, then bail
        if (!parsedValue.isContainsLookups()) {
            return parsedValue;
        }

        final Object val = parsedValue.getValue();
        if (val instanceof ParsedValueImpl[][]) {
        // If ParsedValueImpl is a layered sequence of values, resolve the lookups for each.
            final ParsedValueImpl[][] layers = (ParsedValueImpl[][])val;
            for (int l=0; l<layers.length; l++) {
                for (int ll=0; ll<layers[l].length; ll++) {
                    if (layers[l][ll] == null) continue;
                    layers[l][ll].setResolved(
                        resolveLookups(node, layers[l][ll], states, inlineStyles, whence, styleList)
                    );
                }
            }

        } else if (val instanceof ParsedValueImpl[]) {
        // If ParsedValueImpl is a sequence of values, resolve the lookups for each.
            final ParsedValueImpl[] layer = (ParsedValueImpl[])val;
            for (int l=0; l<layer.length; l++) {
                if (layer[l] == null) continue;
                layer[l].setResolved(
                    resolveLookups(node, layer[l], states, inlineStyles, whence, styleList)
                );
            }
        }

        return parsedValue;

    }
    
    private String getUnresolvedLookup(ParsedValueImpl resolved) {

        Object value = resolved.getValue();

        if (resolved.isLookup() && value instanceof String) {
            return (String)value;
        } 

        if (value instanceof ParsedValueImpl[][]) {
            final ParsedValueImpl[][] layers = (ParsedValueImpl[][])value;
            for (int l=0; l<layers.length; l++) {
                for (int ll=0; ll<layers[l].length; ll++) {
                    if (layers[l][ll] == null) continue;
                    String unresolvedLookup = getUnresolvedLookup(layers[l][ll]);
                    if (unresolvedLookup != null) return unresolvedLookup;
                }
            }

        } else if (value instanceof ParsedValueImpl[]) {
        // If ParsedValueImpl is a sequence of values, resolve the lookups for each.
            final ParsedValueImpl[] layer = (ParsedValueImpl[])value;
            for (int l=0; l<layer.length; l++) {
                if (layer[l] == null) continue;
                String unresolvedLookup = getUnresolvedLookup(layer[l]);
                if (unresolvedLookup != null) return unresolvedLookup;
            }
        }        
        
        return null;
    }
    
    private String formatUnresolvedLookupMessage(Node node, CssMetaData styleable, Style style, ParsedValueImpl resolved) {
        
        // find value that could not be looked up
        String missingLookup = resolved != null ? getUnresolvedLookup(resolved) : null;
        if (missingLookup == null) missingLookup = "a lookup value";
        
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("Could not resolve '")
            .append(missingLookup)
            .append("'")
            .append(" while resolving lookups for '")
            .append(styleable.getProperty())
            .append("'");
        
        final Rule rule = style != null ? style.getDeclaration().getRule(): null;
        final Stylesheet stylesheet = rule != null ? rule.getStylesheet() : null;
        final java.net.URL url = stylesheet != null ? stylesheet.getUrl() : null;
        if (url != null) {
            sbuf.append(" from rule '")
                .append(style.getSelector())
                .append("' in stylesheet ").append(url.toExternalForm());
        } else if (stylesheet != null && StyleOrigin.INLINE == stylesheet.getOrigin()) {
            sbuf.append(" from inline style on " )
                .append(node.toString());            
        }
        
        return sbuf.toString();
    }

    private String formatExceptionMessage(Node node, CssMetaData styleable, Style style, Exception e) {
        
        StringBuilder sbuf = new StringBuilder();
        sbuf.append("Caught ")
            .append(e.toString())
            .append("'")
            .append(" while calculating value for '")
            .append(styleable.getProperty())
            .append("'");
        
        final Rule rule = style != null ? style.getDeclaration().getRule(): null;
        final Stylesheet stylesheet = rule != null ? rule.getStylesheet() : null;
        final java.net.URL url = stylesheet != null ? stylesheet.getUrl() : null;
        if (url != null) {
            sbuf.append(" from rule '")
                .append(style.getSelector())
                .append("' in stylesheet ").append(url.toExternalForm());
        } else if (stylesheet != null && StyleOrigin.INLINE == stylesheet.getOrigin()) {
            sbuf.append(" from inline style on " )
                .append(node.toString());            
        }
        
        return sbuf.toString();
    }
    
    
    private CalculatedValue calculateValue(
            final CascadingStyle style, 
            final Node node, 
            final CssMetaData cssMetaData, 
            final Set<PseudoClass> states,
            final Map<String,CascadingStyle> inlineStyles, 
            final Node originatingNode, 
            final StyleCacheEntry cacheEntry, 
            final List<Style> styleList) {

        final ParsedValueImpl cssValue = style.getParsedValueImpl();
        if (cssValue != null && !("null").equals(cssValue.getValue())) {

            ObjectProperty<StyleOrigin> whence = new SimpleObjectProperty<StyleOrigin>(style.getOrigin());
            final ParsedValueImpl resolved = 
                resolveLookups(node, cssValue, states, inlineStyles, whence, styleList);
            
            try {
                // The computed value
                Object val = null;
                CalculatedValue fontValue = null;

                //
                // Avoid using a font calculated from a relative size
                // to calculate a font with a relative size. 
                // For example:
                // Assume the default font size is 13 and we have a style with
                // -fx-font-size: 1.5em, then the cacheEntry font value will 
                // have a size of 13*1.5=19.5. 
                // Now, when converting that same font size again in response
                // to looking up a value for -fx-font, we do not want to use
                // 19.5 as the font for relative size conversion since this will
                // yield a font 19.5*1.5=29.25 when really what we want is
                // a font size of 19.5.
                // In this situation, then, we use the font from the parent's
                // cache entry.
                final String property = cssMetaData.getProperty();
                if (resolved.isNeedsFont() &&
                    (cacheEntry.getFont() == null || cacheEntry.getFont().isRelative()) &&
                    ("-fx-font".equals(property) ||
                     "-fx-font-size".equals(property)))  {
                    
                    Node parent = node;
                    CalculatedValue cachedFont = cacheEntry.getFont();
                    while(parent != null) {
                        
                        final StyleCacheEntry parentCacheEntry = 
                            getParentCacheEntry(parent);
                        
                        if (parentCacheEntry != null) {
                            fontValue = parentCacheEntry.getFont();
                        
                            if (fontValue != null && fontValue.isRelative()) {

                                // If cacheEntry.font is null, then we're here by 
                                // way of getFontForUseInConvertingRelativeSize
                                // If  the fontValue is not relative, then the
                                // cacheEntry.font needs to be calculated. If
                                // fontValue is relative, then we can use it as
                                // is - this is a hack for Control and SkinBase
                                // which share the same styles (hence the check
                                // for parent == node).
                                // TBD - should check that they are the same styles
    //                                if (parent == node && childCacheEntry.font == null) {
    //                                    return fontValue;
    //                                } 

                                if (cachedFont != null) {
                                    final Font ceFont = (Font)cachedFont.getValue();
                                    final Font fvFont = (Font)fontValue.getValue();
                                    if (ceFont.getSize() != fvFont.getSize()) {
                                        // if the font sizes don't match, then
                                        // the fonts came from distinct styles,
                                        // otherwise, we need another level of
                                        // indirection
                                        break;
                                    }
                                } 

                                // cachedFont is null or the sizes match
                                // (implies the fonts came from the same style)
                                cachedFont = fontValue;

                            } else if (fontValue != null) {
                                // fontValue.isRelative() == false
                                break;
                            }
                        }
                        // try again
                        parent = parent.getParent();
                    }
                }

                // did we get a fontValue from the preceding block (from the hack)?
                // if not, get it from our cachEntry or choose the default.cd
                if (fontValue == null && cacheEntry != null) {
                    fontValue = cacheEntry.getFont();
                }
                final Font font = (fontValue != null) ? (Font)fontValue.getValue() : Font.getDefault();


                if (resolved.getConverter() != null)
                    val = resolved.convert(font);
                else
                    val = cssMetaData.getConverter().convert(resolved, font);

                final StyleOrigin origin = whence.get();
                return new CalculatedValue(val, origin, resolved.isNeedsFont());
                
            } catch (ClassCastException cce) {
                final String msg = formatUnresolvedLookupMessage(node, cssMetaData, style.getStyle(),resolved);
                List<CssError> errors = null;
                if ((errors = StyleManager.getErrors()) != null) {
                    final CssError error = new CssError.PropertySetError(cssMetaData, node, msg);
                    errors.add(error);
                }
                if (LOGGER.isLoggable(PlatformLogger.WARNING)) {
                    LOGGER.warning(msg);
                    LOGGER.fine("node = " + node.toString());
                    LOGGER.fine("cssMetaData = " + cssMetaData);
                    LOGGER.fine("styles = " + getMatchingStyles(node, cssMetaData));
                }
                return SKIP;
            } catch (IllegalArgumentException iae) {
                final String msg = formatExceptionMessage(node, cssMetaData, style.getStyle(), iae);
                List<CssError> errors = null;
                if ((errors = StyleManager.getErrors()) != null) {
                    final CssError error = new CssError.PropertySetError(cssMetaData, node, msg);
                    errors.add(error);
                }
                if (LOGGER.isLoggable(PlatformLogger.WARNING)) {
                    LOGGER.warning("caught: ", iae);
                    LOGGER.fine("styleable = " + cssMetaData);
                    LOGGER.fine("node = " + node.toString());
                }
                return SKIP;
            } catch (NullPointerException npe) {
                final String msg = formatExceptionMessage(node, cssMetaData, style.getStyle(), npe);
                List<CssError> errors = null;
                if ((errors = StyleManager.getErrors()) != null) {
                    final CssError error = new CssError.PropertySetError(cssMetaData, node, msg);
                    errors.add(error);
                }
                if (LOGGER.isLoggable(PlatformLogger.WARNING)) {
                    LOGGER.warning("caught: ", npe);
                    LOGGER.fine("styleable = " + cssMetaData);
                    LOGGER.fine("node = " + node.toString());
                }
                return SKIP;
            } finally {
                resolved.setResolved(null);
            }
                
        }
        // either cssValue was null or cssValue's value was "null"
        return new CalculatedValue(null, style.getOrigin(), false);
           
    }
                
    private static final CssMetaData dummyFontProperty =
            new FontCssMetaData<Node>("-fx-font", Font.getDefault()) {

        @Override
        public boolean isSettable(Node node) {
            return true;
        }

        @Override
        public StyleableProperty<Font> getStyleableProperty(Node node) {
            return null;
        }
    };
   
    private StyleCacheEntry getParentCacheEntry(Node child) {

        if (child == null) return null;
        
        StyleCacheEntry parentCacheEntry = null;
        CssStyleHelper parentHelper = null;
        Node parent = child.getParent();
        
        while(parent != null) {
            
            parentHelper = parent.styleHelper;
            if (parentHelper != null) {
                
                Set<PseudoClass>[] transitionStates = getTransitionStates(parent);                    
                parentCacheEntry = parentHelper.getStyleCacheEntry(parent, transitionStates);
                
                if (parentCacheEntry != null) {
                    
                    if (parentCacheEntry.getFont() == null) {

                        final CalculatedValue relativeFont = 
                            parentHelper.getFontForUseInConvertingRelativeSize(
                                parent, 
                                parentCacheEntry, 
                                transitionStates[0], 
                                getInlineStyleMap(parent)
                            );            

                        assert(relativeFont != null);
                        parentCacheEntry.setFont(relativeFont);

                    }
                    
                    break;
                }
            }
            
            parent = parent.getParent();
        }
        
        return parentCacheEntry;
    }
   
    private CalculatedValue getFontForUseInConvertingRelativeSize(
             final Node node,
             final StyleCacheEntry cacheEntry,
             Set<PseudoClass> pseudoclassState,
            Map<String,CascadingStyle> inlineStyles)
     {
        
        // To make the code easier to read, define CONSIDER_INLINE_STYLES as true. 
        // Passed to lookupFontSubProperty to tell it whether or not 
        // it should look for inline styles
        final boolean CONSIDER_INLINE_STYLES = true;
        
        StyleOrigin origin = null;
        Font foundFont = null;
        CalculatedValue foundSize = null;
        CalculatedValue foundShorthand = null;
        boolean isRelative = false;
         
        // RT-20145 - if looking for font size and the node has a font, 
        // use the font property's value if it was set by the user and
        // there is not an inline or author style.
        if (cacheMetaData.fontProp != null) {
            StyleableProperty<Font> styleableProp = cacheMetaData.fontProp.getStyleableProperty(node);
            StyleOrigin fpOrigin = styleableProp.getStyleOrigin();
            if (fpOrigin == StyleOrigin.USER) {                
                origin = fpOrigin;
                foundFont = styleableProp.getValue();
            }
        } 
 
        final CascadingStyle fontShorthand =
            getStyle(node, "-fx-font", pseudoclassState, inlineStyles);

        if (fontShorthand != null) {
            
            final CalculatedValue cv = 
                calculateValue(fontShorthand, node, dummyFontProperty, 
                    pseudoclassState, inlineStyles, 
                    node, cacheEntry, null);
            
            // If we don't have an existing font, or if the origin of the
            // existing font is less than that of the shorthand, then
            // take the shorthand. If the origins compare equals, then take 
            // the shorthand since the fontProp value will not have been
            // updated yet.
            // Man, this drove me nuts!
            if (origin == null || origin.compareTo(cv.getOrigin()) <= 0) {
                               
                // cv could be SKIP
                if (cv.getValue() instanceof Font) {
                    origin = cv.getOrigin();
                    foundShorthand = cv;
                    foundFont = null;
                }

            }  
         }
 
        final boolean isUserSet = 
                origin == StyleOrigin.USER || origin == StyleOrigin.INLINE;

        // now look for -fx-size, but don't look past the current node (hence 
        // distance == 0 and negation of CONSIDER_INLINE_STYLES)        
        CascadingStyle fontSize = lookupFontSubPropertyStyle(node, "-fx-font-size",
                isUserSet, fontShorthand, 0, !CONSIDER_INLINE_STYLES);
        
        if (fontSize != null) {

            final CalculatedValue cv = 
                calculateValue(fontSize, node, dummyFontProperty, pseudoclassState, inlineStyles, 
                    node, cacheEntry, null);
            
            if (cv.getValue() instanceof Double) {
                if (origin == null || origin.compareTo(cv.getOrigin()) <= 0) {  
                    origin = cv.getOrigin();
                    foundSize = cv;
                    foundShorthand = null;
                    foundFont = null;
                }
             }

         }
         
         
        if (foundShorthand != null) {
            
            return foundShorthand;
            
        } else if (foundSize != null) {
            
            Font font = Font.font("system", ((Double)foundSize.getValue()).doubleValue());
            return new CalculatedValue(font, foundSize.getOrigin(), foundSize.isRelative());
            
        } else if (foundFont != null) {
         
            // Did this found font come from a style? 
            // If so, then the font used to convert it from a relative size
            // (if conversion was needed) comes from the parent.
            if (origin != null && origin != StyleOrigin.USER) {
                
                StyleCacheEntry parentCacheEntry = getParentCacheEntry(node);

                if (parentCacheEntry != null && parentCacheEntry.getFont() != null) {
                    isRelative = parentCacheEntry.getFont().isRelative();
                } 
            }
            return new CalculatedValue(foundFont, origin, isRelative);
            
        } else {
            
            // no font, font-size or fontProp. 
            // inherit by taking the parent's cache entry font.
            StyleCacheEntry parentCacheEntry = getParentCacheEntry(node);
            
            if (parentCacheEntry != null && parentCacheEntry.getFont() != null) {
                return parentCacheEntry.getFont();                    
            } 
        } 
        // last ditch effort -  take the default font.
        return new CalculatedValue(Font.getDefault(), null, false);
     }

    
    private CascadingStyle lookupFontSubPropertyStyle(final Node node, 
            final String subProperty, final boolean isUserSet,
            final CascadingStyle csShorthand, 
            final int distance,
            boolean considerInlineStyles) {
    
        Node parent = node;
        CssStyleHelper helper = this;
        int nlooks = 0;
        CascadingStyle returnValue = null;
        StyleOrigin origin = null;
        boolean consideringInline = false;
        
        while (parent != null && (consideringInline || nlooks <= distance)) {
            
            final Set<PseudoClass> states = parent.pseudoClassStates;
            final Map<String,CascadingStyle> inlineStyles = CssStyleHelper.getInlineStyleMap(parent);
            
            CascadingStyle cascadingStyle = 
                helper.getStyle(parent, subProperty, states, inlineStyles);
            
            if (isUserSet) {
                //
                // Don't look past this node if the user set the property.
                //
                if (cascadingStyle != null) {
                    
                    origin = cascadingStyle.getOrigin();

                    // if the user set the property and the origin is the
                    // user agent stylesheet, then we can't use the style
                    // since ua styles shouldn't override user set values
                    if (origin == StyleOrigin.USER_AGENT) {
                        returnValue = null;
                    }
                }    
                
                break;
                
            } else if (cascadingStyle != null) {

                //
                // If isUserSet is false, then take the style if we found one.
                // If csShorthand is not null, then were looking for an inline
                // style. In this case, if the origin is INLINE, we'll take it.
                // If it isn't INLINE we'll keep looking for an INLINE style.
                //
                final boolean isInline = cascadingStyle.getOrigin() == StyleOrigin.INLINE;
                if (returnValue == null || isInline) {
                    origin = cascadingStyle.getOrigin();
                    returnValue = cascadingStyle;
                    
                    // If we found and inline style, then we don't need to look 
                    // further. Also, if the style is not an inline style and we
                    // don't want to consider inline styles, then look no further.
                    if (isInline || considerInlineStyles == false) {
                        
                        break;
                        
                    } else {
                        // 
                        // If we are here, then the style is not an inline style
                        // and we do want to consider inline styles. Setting
                        // this flag will cause the code to look beyond nlooks
                        // to see if there is an inline style
                        //
                        consideringInline = true;
                    }
                } 
                
            }
                
            // 
            // haven't found it yet, or we're looking for an inline style
            // so keep looking up the parent chain.
            //
            do {
                parent = parent.getParent();
                nlooks += 1;
                helper = parent != null ? parent.styleHelper : null;
            } while (parent != null && helper == null);
        }
        
        if (csShorthand != null && returnValue != null) {
       
            final boolean shorthandImportant = 
                    csShorthand.getStyle().getDeclaration().isImportant();
            
            final Style style = returnValue.getStyle();
            final boolean returnValueIsImportant = 
                style.getDeclaration().isImportant();

            if (nlooks < distance) {
                //
                // if we found a font sub-property closer to the node than 
                // the font shorthand, then the sub-property style
                // wins provided the fontShorthand isn't important.
                // If font shorthand is important and sub-property style is 
                // important, then sub-property style wins (since, being closer
                // to the node, it is more specific)
                //                
                if (shorthandImportant == true && returnValueIsImportant == false) {
                    returnValue = null;
                } 

            } else if (csShorthand.compareTo(returnValue) < 0) {
                //
                // CascadingStyle.compareTo is such that a return value less
                // than zero means the style is more specific than the arg.
                // So if csShorthand is more specific than the return value,
                // return null.
                // 
                // if we found font sub-property at the same distance from the
                // node as the font shortand, then do a normal compare
                // to see if the sub-property is more specific. If it isn't
                // then return null.
                //
                returnValue = null;
                        
            }

        }
        
        return returnValue;
        
    }
    
    /**
     * Look up a font property. This is handled separately from lookup since
     * font is inherited and has sub-properties. One should expect that the 
     * text font for the following would be 16px Arial. The lookup method would
     * give 16px system since it would look <em>only</em> for font-size, 
     * font-family, etc <em>only</em> if the lookup on font failed.
     * <pre>
     * Text text = new Text("Hello World");
     * text.setStyle("-fx-font-size: 16px;");
     * Group group = new Group();
     * group.setStyle("-fx-font: 12px Arial;");
     * group.getChildren().add(text);
     * </pre>
     * @param node
     * @param styleable
     * @param isUserSet
     * @param states
     * @param userStyles
     * @param originatingNode
     * @param cacheEntry
     * @param styleList
     * @return 
     */
    private CalculatedValue lookupFont(Node node, CssMetaData styleable, 
            boolean isUserSet, Node originatingNode, 
            StyleCacheEntry cacheEntry, List<Style> styleList) {

        // To make the code easier to read, define CONSIDER_INLINE_STYLES as true. 
        // Passed to lookupFontSubProperty to tell it whether or not 
        // it should look for inline styles
        final boolean CONSIDER_INLINE_STYLES = true; 
        
        StyleOrigin origin = null;
        boolean isRelative = false;
            
        // distance keeps track of how far up the parent chain we had to go
        // to find a font shorthand. We'll look no further than this
        // for the missing pieces. nlooks is used to keep track of how 
        // many parents we've looked at. nlooks should never exceed distance. 
        int distance = 0, nlooks = 0;
        
        Node parent = node;
        CssStyleHelper helper = this;
        String property = styleable == null ? null : styleable.getProperty();
        CascadingStyle csShorthand = null;
        while (parent != null) {
            
            final Set<PseudoClass> states = parent.pseudoClassStates;
            final Map<String,CascadingStyle> inlineStyles = CssStyleHelper.getInlineStyleMap(parent);
            
            final CascadingStyle cascadingStyle =
                helper.getStyle(parent, property, states, inlineStyles);
            
            if (isUserSet) {
                //
                // If isUserSet, then we don't look beyond the current node. 
                // Only if the user did not set the font will we inherit.
                //
                if (cascadingStyle != null) {
                
                    origin = cascadingStyle.getOrigin();

                    // if the user set font and the origin of the font shorthand
                    // is the user agent stylesheet, then we can't use the style
                    // since ua styles shouldn't override setFont
                    if (origin != StyleOrigin.USER_AGENT) {
                        csShorthand = cascadingStyle;
                    }                    
                }    
                
                break;
                
            } else if (cascadingStyle != null) {
                //
                // If isUserSet is false, then take the style if we found one.
                // If csShorthand is not null, then were looking for an inline
                // style. In this case, if the origin is INLINE, we'll take it.
                // If it isn't INLINE we'll keep looking for an INLINE style.
                //
                final boolean isInline = cascadingStyle.getOrigin() == StyleOrigin.INLINE;
                if (csShorthand == null || isInline) {
                    origin = cascadingStyle.getOrigin();
                    csShorthand = cascadingStyle;
                    distance = nlooks;
                    
                    if (isInline) {
                        break;
                    }
                } 
                
                
            } 
            
            //
            // If were here, then we either didn't find a style or we did find
            // one but it wasn't inline. Either way, we need to keep looking
            // up the parent chain for the next -fx-font.
            //
            do {
                parent = parent.getParent();
                nlooks += 1;
                helper = parent != null ? parent.styleHelper : null;
            } while (parent != null && helper == null);

        }

        if (csShorthand == null) {
            distance = nlooks;
        }
        nlooks = 0;
        
        final Set<PseudoClass> states = node.pseudoClassStates;
        final Map<String,CascadingStyle> inlineStyles = getInlineStyleMap(node);

        String family = null;
        double size = -1;
        FontWeight weight = null;
        FontPosture style = null;
        
        if (csShorthand != null) {
            
            if (styleList != null) {
                styleList.add(csShorthand.getStyle());
            }
            
            // pull out the pieces. 
            final CalculatedValue cv = 
                calculateValue(csShorthand, node, styleable, states, inlineStyles, 
                    originatingNode, cacheEntry, styleList);
            
            // UA < AUTHOR < INLINE
            if (origin == null || origin.compareTo(cv.getOrigin()) <= 0) {
                
                if (cv.getValue() instanceof Font) {
                    Font f = (Font)cv.getValue();
                    isRelative = cv.isRelative();
                    origin = cv.getOrigin();
                
                    // what did font shorthand specify? 
                    ParsedValueImpl[] vals = 
                            (ParsedValueImpl[])csShorthand.getParsedValueImpl().getValue();
                    // Use family and size from converted font since the actual 
                    // values may have been resolved. The weight and posture, 
                    // however, are hard to get reliably from the font so use 
                    // the parsed value instead. 
                    if (vals[0] != null) family = f.getFamily();
                    if (vals[1] != null) size   = f.getSize();
                    if (vals[2] != null) weight = (FontWeight)vals[2].convert(null);
                    if (vals[3] != null) style  = (FontPosture)vals[3].convert(null);

                }
            }
            
        }
        
        CascadingStyle csFamily = null; 
        if ((csFamily = lookupFontSubPropertyStyle(node, property+"-family",
                isUserSet, csShorthand, distance, CONSIDER_INLINE_STYLES)) != null) {

            if (styleList != null) {
                styleList.add(csFamily.getStyle());
            }
            
            final CalculatedValue cv = 
                calculateValue(csFamily, node, styleable, states, inlineStyles, 
                    originatingNode, cacheEntry, styleList);

            if (origin == null || origin.compareTo(cv.getOrigin()) <= 0) {                        
                if (cv.getValue() instanceof String) {
                    family = Utils.stripQuotes((String)cv.getValue());
                    origin = cv.getOrigin();
                }
            }
                
        }
        
        CascadingStyle csSize = null;
        if ((csSize = lookupFontSubPropertyStyle(node, property+"-size",
                isUserSet, csShorthand, distance, CONSIDER_INLINE_STYLES))!= null) {
       
            if (styleList != null) {
                styleList.add(csSize.getStyle());
            }

            final CalculatedValue cv = 
                calculateValue(csSize, node, styleable, states, inlineStyles, 
                    originatingNode, cacheEntry, styleList);

            // UA < AUTHOR < INLINE
            if (origin == null || origin.compareTo(cv.getOrigin()) <= 0) {                        
                if (cv.getValue() instanceof Double) {
                    size = ((Double)cv.getValue()).doubleValue();
                    isRelative = cv.isRelative();
                    origin = cv.getOrigin();
                }
            }

        }
        
        CascadingStyle csWeight = null;
        if ((csWeight = lookupFontSubPropertyStyle(node, property+"-weight",
                isUserSet, csShorthand, distance, CONSIDER_INLINE_STYLES))!= null) {

            if (styleList != null) {
                styleList.add(csWeight.getStyle());
            }
            
            final CalculatedValue cv = 
                calculateValue(csWeight, node, styleable, states, inlineStyles, 
                    originatingNode, cacheEntry, styleList);

            // UA < AUTHOR < INLINE
            if (origin == null || origin.compareTo(cv.getOrigin()) <= 0) {                        
                if (cv.getValue() instanceof FontWeight) {
                    weight = (FontWeight)cv.getValue();
                    origin = cv.getOrigin();
                }
            }
                
        }

        CascadingStyle csStyle = null;
        if ((csStyle = lookupFontSubPropertyStyle(node, property+"-style",
                isUserSet, csShorthand, distance, CONSIDER_INLINE_STYLES))!= null) {
            
            if (styleList != null) {
                styleList.add(csStyle.getStyle());
            }
            
            final CalculatedValue cv = 
                calculateValue(csStyle, node, styleable, states, inlineStyles, 
                    originatingNode, cacheEntry, styleList);

            // UA < AUTHOR < INLINE
            if (origin == null || origin.compareTo(cv.getOrigin()) <= 0) {                        
                if (cv.getValue() instanceof FontPosture) {
                    style = (FontPosture)cv.getValue();
                    origin = cv.getOrigin();
                }
            }                

        }
        
        // if no styles were found, then skip...
        if (family == null &&
            size   == -1   &&
            weight == null &&
            style  == null) {
            return SKIP;
        }
        
        // Now we have all the pieces from the stylesheet
        // still be some missing. We'll grab those from the node. 
        StyleableProperty styleableProperty = styleable != null 
                ? styleable.getStyleableProperty(node) 
                : null;
        Font f = null;
        if (styleableProperty != null) {
            f = (Font)styleableProperty.getValue();
        }
        if (f == null) f = Font.getDefault();

        if (family == null) {
            family = f.getFamily();
        }

        if (size == -1) {
            size = f.getSize();                
        }

        Font val = null;
        if (weight != null && style != null) {
            val = Font.font(family, weight, style, size);            
        } else if (weight != null) {
            val = Font.font(family, weight, size);
        } else if (style != null) {
            val = Font.font(family, style, size);
        } else {
            val = Font.font(family, size);            
        }

        return new CalculatedValue(val, origin, isRelative);
    }    
    
    /**
     * Called from CssMetaData getMatchingStyles
     * @param node
     * @param styleableProperty
     * @return 
     */
    List<Style> getMatchingStyles(Styleable node, CssMetaData styleableProperty) {
        
        final List<CascadingStyle> styleList = new ArrayList<CascadingStyle>();

        getMatchingStyles(node, styleableProperty, styleList);

        List<CssMetaData<? extends Styleable, ?>> subProperties = styleableProperty.getSubProperties();
        if (subProperties != null) {
            for (int n=0,nMax=subProperties.size(); n<nMax; n++) {
                final CssMetaData subProperty = subProperties.get(n);
                getMatchingStyles(node, subProperty, styleList);                    
            }
        }

        Collections.sort(styleList);

        final List<Style> matchingStyles = new ArrayList<Style>(styleList.size());
        for (int n=0,nMax=styleList.size(); n<nMax; n++) {
            final Style style = styleList.get(n).getStyle();
            if (!matchingStyles.contains(style)) matchingStyles.add(style);
        }

        return matchingStyles;
    }
    
    private void getMatchingStyles(Styleable node, CssMetaData styleableProperty, List<CascadingStyle> styleList) {
        
        if (node != null) {
            
            Map<String,List<CascadingStyle>> inlineStyleMap = null;
            
            // create a map of inline styles.
            Styleable parent = node;
            while (parent != null) {
                
                CssStyleHelper parentHelper = (parent instanceof Node) ?
                        ((Parent)parent).styleHelper
                        : null;
                
                if (parentHelper != null) {
                    
                    Map<String,CascadingStyle> inlineStyles = CssStyleHelper.getInlineStyleMap(parent);
                    
                    if (inlineStyles != null) {
                        
                        if (inlineStyleMap == null) {
                            inlineStyleMap = new HashMap<String,List<CascadingStyle>>();
                        }
                        
                        for(Entry<String,CascadingStyle> entry : inlineStyles.entrySet()) {                            
                            String kee = entry.getKey();
                            
                            List<CascadingStyle> inlineStyleList = inlineStyleMap.get(kee);
                            if (inlineStyleList == null) {
                                inlineStyleList = new ArrayList<CascadingStyle>();
                                inlineStyleMap.put(kee, inlineStyleList);
                            }
                            inlineStyleList.add(entry.getValue());
                        }
                    }
                }
                parent = parent.getStyleableParent();
            }
                    
            String property = styleableProperty.getProperty();
            final Map<String, List<CascadingStyle>> smap = getStyleMap();
            if (smap == null) return;
            
             List<CascadingStyle> styles = smap.get(property);            
            
//            if (inlineStyleMap != null) {
//               if (inlineStyleMap.containsKey(property)) {
//                    List<CascadingStyle> inlineStyleList = inlineStyleMap.get(property);
//                    if (styles == null) styles = new ArrayList<CascadingStyle>();
//                    styles.addAll(inlineStyleList);
//                }
//            }

            if (styles != null) {
                styleList.addAll(styles);
                for (int n=0, nMax=styles.size(); n<nMax; n++) {
                    final CascadingStyle style = styles.get(n);
                    final ParsedValueImpl parsedValue = style.getParsedValueImpl();
                    getMatchingLookupStyles(node, parsedValue, inlineStyleMap, styleList);
                }
            }
            
            if (styleableProperty.isInherits()) {
                parent = node.getStyleableParent();
                while (parent != null) {
                    CssStyleHelper parentHelper = parent instanceof Node 
                            ? ((Node)parent).styleHelper
                            : null;
                    if (parentHelper != null) {
                        parentHelper.getMatchingStyles(parent, styleableProperty, styleList); 
                    }
                    parent = parent.getStyleableParent();
                }
            }
            
        }

    }
    
    // Pretty much a duplicate of resolveLookups, but without the state
    private void getMatchingLookupStyles(Styleable node, ParsedValueImpl parsedValue, Map<String,List<CascadingStyle>> inlineStyleMap, List<CascadingStyle> styleList) {
                
        if (parsedValue.isLookup()) {
            
            Object value = parsedValue.getValue();
            
            if (value instanceof String) {
                
                final String property = (String)value;
                // gather up any and all styles that contain this value as a property
                Styleable parent = node;
                do {
                    final CssStyleHelper helper = parent instanceof Node 
                            ? ((Node)parent).styleHelper
                            : null;
                    if (helper != null) {
                                             
                        final int start = styleList.size();
                        
                        final Map<String, List<CascadingStyle>> smap = helper.getStyleMap();
                        if (smap != null) {

                            List<CascadingStyle> styles = smap.get(property);

                            if (styles != null) {
                                styleList.addAll(styles);
                            }

                        }
                        
                        List<CascadingStyle> inlineStyles = (inlineStyleMap != null) 
                            ? inlineStyleMap.get(property) 
                            : null;
                        
                        if (inlineStyles != null) {
                            styleList.addAll(inlineStyles);
                        }
                        
                        final int end = styleList.size();
                        
                        for (int index=start; index<end; index++) {
                            final CascadingStyle style = styleList.get(index);
                            getMatchingLookupStyles(parent, style.getParsedValueImpl(), inlineStyleMap, styleList);
                        }
                    }
                                                                                    
                } while ((parent = parent.getStyleableParent()) != null);
            
            }
        }
        
        // If the value doesn't contain any values that need lookup, then bail
        if (!parsedValue.isContainsLookups()) {
            return;
        }

        final Object val = parsedValue.getValue();
        if (val instanceof ParsedValueImpl[][]) {
        // If ParsedValueImpl is a layered sequence of values, resolve the lookups for each.
            final ParsedValueImpl[][] layers = (ParsedValueImpl[][])val;
            for (int l=0; l<layers.length; l++) {
                for (int ll=0; ll<layers[l].length; ll++) {
                    if (layers[l][ll] == null) continue;
                        getMatchingLookupStyles(node, layers[l][ll], inlineStyleMap, styleList);
                }
            }

        } else if (val instanceof ParsedValueImpl[]) {
        // If ParsedValueImpl is a sequence of values, resolve the lookups for each.
            final ParsedValueImpl[] layer = (ParsedValueImpl[])val;
            for (int l=0; l<layer.length; l++) {
                if (layer[l] == null) continue;
                    getMatchingLookupStyles(node, layer[l], inlineStyleMap, styleList);
            }
        }

    }
    
}