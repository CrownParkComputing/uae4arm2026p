#pragma once

#include <string>
#include <guisan.hpp>

/**
 * SelectorEntry - A clickable sidebar selector button widget used for
 * navigating between GUI panels (left-side category list).
 */
class SelectorEntry : public gcn::Widget,
                      public gcn::MouseListener
{
public:
    explicit SelectorEntry(const std::string& caption)
        : mCaption(caption), mActive(false)
    {
        addMouseListener(this);
        setFocusable(true);
        setHeight(30);
    }

    void setCaption(const std::string& caption) { mCaption = caption; }
    const std::string& getCaption() const { return mCaption; }

    void setActive(bool active) { mActive = active; }
    bool isActive() const { return mActive; }

    void setActiveColor(const gcn::Color& color)   { mActiveColor   = color; }
    void setInactiveColor(const gcn::Color& color) { mInactiveColor = color; }

    void draw(gcn::Graphics* graphics) override
    {
        const gcn::Color& bg = mActive ? mActiveColor : mInactiveColor;
        graphics->setColor(bg);
        graphics->fillRectangle(gcn::Rectangle(0, 0, getWidth(), getHeight()));

        graphics->setColor(mActive ? gcn::Color(255, 255, 255) : getForegroundColor());
        graphics->setFont(getFont());

        const int tx = 8;
        const int ty = (getHeight() - getFont()->getHeight()) / 2;
        graphics->drawText(mCaption, tx, ty);
    }

    void mouseClicked(gcn::MouseEvent& mouseEvent) override
    {
        if (mouseEvent.getButton() == gcn::MouseEvent::Left)
            distributeActionEvent();
    }
    void mouseDragged(gcn::MouseEvent&) override {}  // required by MouseListener; no action needed

protected:
    std::string mCaption;
    bool        mActive       = false;
    gcn::Color  mActiveColor   = { 103, 136, 187 };
    gcn::Color  mInactiveColor = { 170, 170, 170 };
};
