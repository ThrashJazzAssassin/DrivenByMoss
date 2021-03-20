// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2021
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.framework.view;

import de.mossgrabers.framework.configuration.Configuration;
import de.mossgrabers.framework.controller.ButtonID;
import de.mossgrabers.framework.controller.IControlSurface;
import de.mossgrabers.framework.controller.grid.IPadGrid;
import de.mossgrabers.framework.controller.hardware.IHwButton;
import de.mossgrabers.framework.daw.IModel;
import de.mossgrabers.framework.daw.INoteClip;
import de.mossgrabers.framework.daw.IStepInfo;
import de.mossgrabers.framework.daw.constants.Resolution;
import de.mossgrabers.framework.daw.data.ITrack;
import de.mossgrabers.framework.daw.data.bank.ITrackBank;
import de.mossgrabers.framework.utils.ButtonEvent;

import java.util.HashMap;
import java.util.Map;


/**
 * The abstract Poly Sequencer view.
 *
 * @param <S> The type of the control surface
 * @param <C> The type of the configuration
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
public abstract class AbstractPolySequencerView<S extends IControlSurface<C>, C extends Configuration> extends AbstractSequencerView<S, C> implements TransposeView
{
    protected static final int            GRID_COLUMNS        = 8;
    protected static final int            GRID_ROWS           = 8;
    protected static final int            NUM_SEQUENCER_LINES = 4;

    protected final int                   sequencerSteps;
    protected final Map<Integer, Integer> noteMemory          = new HashMap<> ();
    protected int                         copyStep            = -1;
    private int                           numColumns;
    private int                           numRows;


    /**
     * Constructor.
     *
     * @param surface The surface
     * @param model The model
     * @param useDawColors True to use the color of the current track for coloring the octaves
     */
    protected AbstractPolySequencerView (final S surface, final IModel model, final boolean useDawColors)
    {
        this (surface, model, useDawColors, GRID_COLUMNS, GRID_ROWS, NUM_SEQUENCER_LINES);
    }


    /**
     * Constructor.
     *
     * @param surface The surface
     * @param model The model
     * @param useDawColors True to use the color of the current track for coloring the octaves
     * @param numColumns The number of columns of the grid
     * @param numRows The number of rows of the grid
     * @param numSequencerRows The number of rows to use for the sequencer (rest is for the play
     *            area)
     */
    protected AbstractPolySequencerView (final S surface, final IModel model, final boolean useDawColors, final int numColumns, final int numRows, final int numSequencerRows)
    {
        super (Views.NAME_POLY_SEQUENCER, surface, model, 128, numSequencerRows * numColumns, useDawColors);

        this.sequencerSteps = numSequencerRows * numColumns;

        this.numColumns = numColumns;
        this.numRows = numRows;
        this.numSequencerRows = numSequencerRows;

        final ITrackBank tb = model.getTrackBank ();
        tb.addSelectionObserver ( (index, isSelected) -> this.keyManager.clearPressedKeys ());
        tb.addNoteObserver (this.keyManager::call);
    }


    /** {@inheritDoc} */
    @Override
    public void onOctaveDown (final ButtonEvent event)
    {
        if (event != ButtonEvent.DOWN)
            return;

        if (this.surface.isShiftPressed ())
        {
            this.getClip ().transpose (-1);
            return;
        }

        if (this.surface.isSelectPressed ())
        {
            this.getClip ().transpose (-12);
            return;
        }

        this.keyManager.clearPressedKeys ();
        this.scales.decOctave ();
        this.updateNoteMapping ();
        this.surface.getDisplay ().notify (this.scales.getRangeText ());
    }


    /** {@inheritDoc} */
    @Override
    public void onOctaveUp (final ButtonEvent event)
    {
        if (event != ButtonEvent.DOWN)
            return;

        if (this.surface.isShiftPressed ())
        {
            this.getClip ().transpose (1);
            return;
        }

        if (this.surface.isSelectPressed ())
        {
            this.getClip ().transpose (12);
            return;
        }

        this.keyManager.clearPressedKeys ();
        this.scales.incOctave ();
        this.updateNoteMapping ();
        this.surface.getDisplay ().notify (this.scales.getRangeText ());
    }


    /** {@inheritDoc} */
    @Override
    public boolean isOctaveUpButtonOn ()
    {
        return true;
    }


    /** {@inheritDoc} */
    @Override
    public boolean isOctaveDownButtonOn ()
    {
        return true;
    }


    /** {@inheritDoc} */
    @Override
    public void onGridNote (final int note, final int velocity)
    {
        final boolean isKeyboardEnabled = this.model.canSelectedTrackHoldNotes ();
        if (!isKeyboardEnabled)
            return;

        final int index = note - 36;
        final int x = index % this.numColumns;
        final int y = index / this.numColumns;

        if (y < this.numRows - this.numSequencerRows)
        {
            this.handleNoteArea (note, velocity);
            return;
        }

        // Only allow playing the notes if there is no clip
        if (!this.isActive ())
            return;

        // Toggle the note on up, so we can intercept the long presses (but not yet used)
        if (velocity > 0)
            return;

        this.handleSequencerArea (x, y);
    }


    /**
     * Handle button presses in the note area of the poly sequencer.
     *
     * @param note The played note
     * @param velocity The velocity
     */
    protected void handleNoteArea (final int note, final int velocity)
    {
        // No pressed keys? Clear up the note memory for programming the sequencer...
        if (!this.keyManager.hasPressedKeys ())
            this.noteMemory.clear ();

        // Mark selected notes immediately for better performance
        final int mappedNote = this.keyManager.map (note);
        if (mappedNote == -1)
            return;

        this.keyManager.setAllKeysPressed (mappedNote, velocity);
        if (velocity > 0)
            this.noteMemory.put (Integer.valueOf (mappedNote), Integer.valueOf (velocity));
    }


    /**
     * Handle pads pressed in the sequencer area.
     *
     * @param x The x position of the pad
     * @param y The y position of the pad
     */
    protected void handleSequencerArea (final int x, final int y)
    {
        final INoteClip clip = this.getClip ();
        final int step = this.numColumns * (this.numRows - 1 - y) + x;
        final int channel = this.configuration.getMidiEditChannel ();

        if (this.handleSequencerAreaButtonCombinations (clip, channel, step))
            return;

        if (this.getStep (clip, step) > 0)
        {
            for (int row = 0; row < 128; row++)
            {
                if (clip.getStep (channel, step, row).getState () > 0)
                    clip.clearStep (channel, step, row);
            }
        }
        else
        {
            for (int row = 0; row < 128; row++)
            {
                final Integer k = Integer.valueOf (row);
                if (this.noteMemory.containsKey (k))
                {
                    final Integer vel = this.noteMemory.get (k);
                    clip.toggleStep (channel, step, row, this.configuration.isAccentActive () ? this.configuration.getFixedAccentValue () : vel.intValue ());
                }
            }
        }
    }


    /**
     * Handle button combinations in the sequencer area.
     *
     * @param clip The sequenced midi clip
     * @param channel The MIDI channel of the note
     * @param step The step in the current page in the clip
     * @return True if handled
     */
    protected boolean handleSequencerAreaButtonCombinations (final INoteClip clip, final int channel, final int step)
    {
        // Handle note duplicate function
        if (this.isButtonCombination (ButtonID.DUPLICATE))
        {
            if (this.getStep (clip, step) == IStepInfo.NOTE_START)
                this.copyStep = step;
            else if (this.copyStep >= 0)
            {
                for (int row = 0; row < 128; row++)
                {
                    final IStepInfo stepInfo = clip.getStep (channel, this.copyStep, row);
                    if (stepInfo != null && stepInfo.getVelocity () > 0)
                        clip.setStep (channel, step, row, stepInfo);
                }
            }
            return true;
        }

        // Change length of a note or create a new one with a length
        for (int s = step - 1; s >= 0; s--)
        {
            final int x = s % this.numColumns;
            final int y = this.numRows - 1 - s / this.numColumns;
            final int pad = y * this.numColumns + x;
            final IHwButton button = this.surface.getButton (ButtonID.get (ButtonID.PAD1, pad));
            if (button.isLongPressed ())
            {
                button.setConsumed ();
                final int length = step - s + 1;
                final double duration = length * Resolution.getValueAt (this.getResolutionIndex ());

                // Create new note(s)
                if (this.getStep (clip, s) != IStepInfo.NOTE_START)
                {
                    for (int row = 0; row < 128; row++)
                    {
                        final Integer k = Integer.valueOf (row);
                        if (this.noteMemory.containsKey (k))
                        {
                            final Integer vel = this.noteMemory.get (k);
                            clip.setStep (channel, s, row, this.configuration.isAccentActive () ? this.configuration.getFixedAccentValue () : vel.intValue (), duration);
                        }
                    }
                    return true;
                }

                // Change length of existing notes
                for (int row = 0; row < 128; row++)
                {
                    final IStepInfo stepInfo = clip.getStep (channel, s, row);
                    if (stepInfo != null && stepInfo.getState () == IStepInfo.NOTE_START)
                        clip.updateStepDuration (channel, s, row, duration);
                }

                return true;
            }
        }

        return false;
    }


    /** {@inheritDoc} */
    @Override
    public void drawGrid ()
    {
        final IPadGrid padGrid = this.surface.getPadGrid ();

        final boolean isKeyboardEnabled = this.model.canSelectedTrackHoldNotes ();
        if (!isKeyboardEnabled)
        {
            padGrid.turnOff ();
            return;
        }

        final INoteClip clip = this.getClip ();
        final boolean isActive = this.isActive ();
        final int step = clip.getCurrentStep ();

        // Paint the sequencer steps
        final int hiStep = this.isInXRange (step) ? step % this.sequencerSteps : -1;
        for (int col = 0; col < this.sequencerSteps; col++)
        {
            final int isSet = this.getStep (clip, col);
            final boolean hilite = col == hiStep;
            final int x = col % this.numColumns;
            final int y = col / this.numColumns;
            padGrid.lightEx (x, y, isActive ? this.getStepColor (isSet, hilite) : AbstractSequencerView.COLOR_NO_CONTENT);
        }

        // Paint the play part
        final boolean isRecording = this.model.hasRecordingState ();
        final ITrack cursorTrack = this.model.getCursorTrack ();
        final int startNote = this.scales.getStartNote ();
        for (int i = startNote; i < startNote + this.sequencerSteps; i++)
            padGrid.light (i, this.getGridColor (isKeyboardEnabled, isRecording, cursorTrack, i));

    }


    /** {@inheritDoc} */
    @Override
    public void updateNoteMapping ()
    {
        final int [] matrix = this.scales.getNoteMatrix ();
        for (int i = this.scales.getStartNote () + this.sequencerSteps; i < this.scales.getEndNote (); i++)
            matrix[i] = -1;
        this.delayedUpdateNoteMapping (matrix);
    }


    /**
     * Check if any note is set at the current step.
     *
     * @param clip The clip which contains the notes
     * @param col The column/step to check
     * @return 0: All notes are off, 1: at least 1 note continues playing, 2: at least 1 note starts
     *         at this step, see the defined constants
     */
    protected int getStep (final INoteClip clip, final int col)
    {
        int result = IStepInfo.NOTE_OFF;
        final int editMidiChannel = this.configuration.getMidiEditChannel ();
        for (int row = 0; row < 128; row++)
        {
            final int r = clip.getStep (editMidiChannel, col, row).getState ();
            if (r == IStepInfo.NOTE_START)
                return r;
            if (r == IStepInfo.NOTE_CONTINUE)
                result = IStepInfo.NOTE_CONTINUE;
        }
        return result;
    }


    /**
     * Get the step color.
     *
     * @param isSet True if the note is set
     * @param hilite True if note should be highlighted
     * @return The color identifier
     */
    protected String getStepColor (final int isSet, final boolean hilite)
    {
        switch (isSet)
        {
            // Note continues
            case IStepInfo.NOTE_CONTINUE:
                return hilite ? AbstractSequencerView.COLOR_STEP_HILITE_CONTENT : AbstractSequencerView.COLOR_CONTENT_CONT;
            // Note starts
            case IStepInfo.NOTE_START:
                return hilite ? AbstractSequencerView.COLOR_STEP_HILITE_CONTENT : AbstractSequencerView.COLOR_CONTENT;
            // Empty
            case IStepInfo.NOTE_OFF:
            default:
                return hilite ? AbstractSequencerView.COLOR_STEP_HILITE_NO_CONTENT : AbstractSequencerView.COLOR_NO_CONTENT;
        }
    }


    /**
     * Get the color for a pad.
     *
     * @param isKeyboardEnabled Can we play?
     * @param isRecording Is recording enabled?
     * @param track The track to use the color for octaves
     * @param note The note of the pad
     * @return The ID of the color
     */
    protected String getGridColor (final boolean isKeyboardEnabled, final boolean isRecording, final ITrack track, final int note)
    {
        if (isKeyboardEnabled)
        {
            if (this.keyManager.isKeyPressed (note))
                return isRecording ? AbstractPlayView.COLOR_RECORD : AbstractPlayView.COLOR_PLAY;
            return this.getPadColor (note, this.useDawColors ? track : null);
        }
        return AbstractPlayView.COLOR_OFF;
    }
}