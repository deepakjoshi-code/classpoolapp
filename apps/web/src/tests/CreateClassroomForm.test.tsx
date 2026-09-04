import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CreateClassroomForm } from "@/components/CreateClassroomForm";
import { api } from "@/lib/api/client";
import type { Classroom } from "@/lib/api/types";

vi.mock("@/lib/api/client", () => ({
  api: {
    GET: vi.fn(),
    POST: vi.fn(),
  },
  API_BASE_URL: "/api/v1",
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

const mockedApi = vi.mocked(api);

const baseClassroom: Classroom = {
  id: "11111111-1111-1111-1111-111111111111",
  schoolId: "school-1",
  schoolName: "Lincoln Elementary",
  schoolYearLabel: "2026-2027",
  grade: "Grade 1",
  teacherLabel: "Ms. Smith",
  studentCountEstimate: 24,
  createdAt: new Date().toISOString(),
  pools: [],
};

async function fillRequiredFields() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/school name/i), "Lincoln Elementary");
  await user.type(screen.getByLabelText(/^grade$/i), "Grade 1");
  await user.type(screen.getByLabelText(/teacher name/i), "Ms. Smith");
  return user;
}

describe("CreateClassroomForm", () => {
  beforeEach(() => {
    mockedApi.GET.mockReset();
    mockedApi.POST.mockReset();
    mockedApi.GET.mockResolvedValue({ data: [], error: undefined } as any);
  });

  it("renders the required fields and lets the organizer submit", async () => {
    mockedApi.POST.mockResolvedValue({
      data: { classroom: baseClassroom, dedupWarning: null },
      error: undefined,
    } as any);

    const onCreated = vi.fn();
    render(<CreateClassroomForm onCreated={onCreated} />);

    expect(screen.getByLabelText(/school name/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^grade$/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/teacher name/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/teacher email/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/school year/i)).toBeInTheDocument();

    const user = await fillRequiredFields();
    await user.click(screen.getByRole("button", { name: /create class/i }));

    await waitFor(() => expect(mockedApi.POST).toHaveBeenCalledTimes(1));
    expect(mockedApi.POST).toHaveBeenCalledWith(
      "/classrooms",
      expect.objectContaining({
        body: expect.objectContaining({
          schoolName: "Lincoln Elementary",
          grade: "Grade 1",
          teacherLabel: "Ms. Smith",
        }),
      })
    );

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(baseClassroom));
  });

  it("shows the dedup warning instead of continuing straight through, and lets the organizer act on it", async () => {
    const duplicate: Classroom = {
      ...baseClassroom,
      id: "22222222-2222-2222-2222-222222222222",
    };

    mockedApi.POST.mockResolvedValue({
      data: { classroom: baseClassroom, dedupWarning: [duplicate] },
      error: undefined,
    } as any);

    const onCreated = vi.fn();
    render(<CreateClassroomForm onCreated={onCreated} />);

    const user = await fillRequiredFields();
    await user.click(screen.getByRole("button", { name: /create class/i }));

    // The dedup warning is shown, not silently discarded.
    expect(
      await screen.findByText(/is this already your class/i)
    ).toBeInTheDocument();
    expect(screen.getAllByText(/Ms\. Smith/).length).toBeGreaterThan(0);

    // onCreated must NOT have fired yet — the organizer hasn't decided.
    expect(onCreated).not.toHaveBeenCalled();

    // "No, create a new one anyway" proceeds with the classroom that was created.
    await user.click(
      screen.getByRole("button", { name: /no, create a new one anyway/i })
    );
    expect(onCreated).toHaveBeenCalledWith(baseClassroom);
  });

  it("offers a 'yes, this is mine' path that does not silently discard the warning", async () => {
    const duplicate: Classroom = {
      ...baseClassroom,
      id: "33333333-3333-3333-3333-333333333333",
    };
    mockedApi.POST.mockResolvedValue({
      data: { classroom: baseClassroom, dedupWarning: [duplicate] },
      error: undefined,
    } as any);

    const onCreated = vi.fn();
    render(<CreateClassroomForm onCreated={onCreated} />);

    const user = await fillRequiredFields();
    await user.click(screen.getByRole("button", { name: /create class/i }));

    await screen.findByText(/is this already your class/i);
    await user.click(
      screen.getByRole("button", { name: /yes, one of these is mine/i })
    );

    expect(
      screen.getByPlaceholderText(/classpool.app\/join/i)
    ).toBeInTheDocument();
    expect(onCreated).not.toHaveBeenCalled();
  });
});
